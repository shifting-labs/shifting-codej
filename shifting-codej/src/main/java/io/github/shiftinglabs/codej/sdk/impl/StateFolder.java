/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.shiftinglabs.codej.sdk.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.impl.DefaultProcessSupervisor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * An agent's state folder: the records of the processes its sessions start and each session's working files. One program at a time
 * holds a folder, and agents that share it in that program share its supervisor. Opening it first stops whatever an earlier run left
 * running; a process that cannot be shown stopped keeps its session's working directory listed until it can.
 */
final class StateFolder
{
    private static final Map<Path, StateFolder> OPEN = new ConcurrentHashMap<>();
    private static final String WORKING_DIRECTORY = "working-directory";
    private static final int SESSION_ID_LENGTH = 36;

    private final Path root;
    private final FileLock lock;
    private final ProcessSupervisor supervisor;
    private final ConversationCosts costs;
    /** Launches an earlier run left that could not be shown stopped, with the owner each names. */
    private final Map<String, Optional<ProcessOwner>> unverified = new ConcurrentHashMap<>();

    static StateFolder open(Path directory)
    {
        Path root = directory.toAbsolutePath().normalize();
        try {
            return OPEN.computeIfAbsent(root, StateFolder::new);
        }
        catch (UncheckedIOException failure) {
            throw new AgentException("The state folder could not be prepared: " + root, failure.getCause());
        }
    }

    StateFolder(Path root)
    {
        this.root = root;
        try {
            Files.createDirectories(root);
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            this.lock = lock(root);
            Path processes = Files.createDirectories(root.resolve("processes"));
            this.supervisor = new DefaultProcessSupervisor(Files.createDirectories(processes.resolve("markers")),
                    new FileLaunchIntents(Files.createDirectories(processes.resolve("intents"))));
            Files.createDirectories(root.resolve("sessions"));
            this.costs = new ConversationCosts(Files.createDirectories(root.resolve("costs")));
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        supervisor.recoverOwnedProcesses().forEach(this::settle);
        deleteEndedSessions();
    }

    private static FileLock lock(Path root)
            throws IOException
    {
        FileChannel channel = FileChannel.open(root.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock held = channel.tryLock();
            if (held != null) {
                return held;
            }
        }
        catch (OverlappingFileLockException inThisProgram) {
            // Only a test opens one folder twice; the program's own agents share the first.
        }
        channel.close();
        throw new AgentException("Another program is using the state folder " + root);
    }

    Path root()
    {
        return root;
    }

    ProcessSupervisor supervisor()
    {
        return supervisor;
    }

    ConversationCosts costs()
    {
        return costs;
    }

    /** A new folder for one session's provider settings and temporary files, naming the directory it works in. */
    Path sessionDirectory(String id, Path workingDirectory)
    {
        try {
            Path directory = Files.createDirectories(root.resolve("sessions").resolve(id));
            Files.writeString(directory.resolve(WORKING_DIRECTORY), workingDirectory.toString());
            return directory;
        }
        catch (IOException failure) {
            throw new AgentException("The session folder could not be prepared", failure);
        }
    }

    /** Checks the earlier run's unsettled processes again, and names the directories they may still change. */
    Set<Path> unconfirmedDirectories()
    {
        ImmutableList.copyOf(unverified.keySet()).forEach(launchId -> settle(supervisor.recoverOwnedProcess(launchId)));
        return directories(unverified.values().stream());
    }

    Set<Path> directories(Stream<Optional<ProcessOwner>> owners)
    {
        return owners.flatMap(Optional::stream)
                .flatMap(owner -> sessionId(owner).stream())
                .flatMap(id -> workingDirectory(id).stream())
                .collect(ImmutableSet.toImmutableSet());
    }

    /** Releases the folder, as the end of this program would; only for a test that plays a second run. */
    void releaseForTest()
            throws IOException
    {
        lock.release();
        lock.channel().close();
    }

    private void settle(OwnedProcessRecovery recovery)
    {
        if (recovery.outcome() == OwnedProcessRecovery.Outcome.UNVERIFIABLE) {
            unverified.put(recovery.launchId(), recovery.owner());
            return;
        }
        supervisor.acknowledge(recovery.launchId());
        unverified.remove(recovery.launchId());
        recovery.owner().flatMap(StateFolder::sessionId).ifPresent(id -> delete(root.resolve("sessions").resolve(id)));
    }

    /** A session folder no unsettled launch names belongs to a session that ended, or whose process is now shown stopped. */
    private void deleteEndedSessions()
    {
        Set<String> kept = unverified.values().stream().flatMap(Optional::stream).flatMap(owner -> sessionId(owner).stream()).collect(ImmutableSet.toImmutableSet());
        try (Stream<Path> sessions = Files.list(root.resolve("sessions"))) {
            sessions.filter(session -> !kept.contains(session.getFileName().toString())).forEach(StateFolder::delete);
        }
        catch (IOException failure) {
            System.getLogger(StateFolder.class.getName()).log(System.Logger.Level.WARNING, "Could not list " + root.resolve("sessions"), failure);
        }
    }

    /** Sessions own their processes by the session's id, or by a turn id that starts with it. */
    static Optional<String> sessionId(ProcessOwner owner)
    {
        String id = owner.id();
        if (id.length() < SESSION_ID_LENGTH) {
            return Optional.empty();
        }
        try {
            String session = id.substring(0, SESSION_ID_LENGTH);
            return Optional.of(UUID.fromString(session).toString()).filter(session::equals);
        }
        catch (IllegalArgumentException notASession) {
            return Optional.empty();
        }
    }

    private Optional<Path> workingDirectory(String sessionId)
    {
        try {
            return Optional.of(Path.of(Files.readString(root.resolve("sessions").resolve(sessionId).resolve(WORKING_DIRECTORY))));
        }
        catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    static void delete(Path sessionDirectory)
    {
        try {
            if (Files.exists(sessionDirectory)) {
                // macOS has no secure directory stream; the folder is this agent's own.
                MoreFiles.deleteRecursively(sessionDirectory, RecursiveDeleteOption.ALLOW_INSECURE);
            }
        }
        catch (IOException failure) {
            System.getLogger(StateFolder.class.getName()).log(System.Logger.Level.WARNING, "Could not delete " + sessionDirectory, failure);
        }
    }
}

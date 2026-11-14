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
package io.github.shiftinglabs.codej.cli.api;

import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One acquired writer. Checkpoints are private, size-bounded, atomically replaced and fsynced before acknowledgement. */
final class ApiSessionStore
        implements ApiSessionRepository
{
    private final Path directory;
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private final ApiEngineConfig config;
    private ApiCheckpoint state;
    private boolean closed;

    private ApiSessionStore(Path directory, FileChannel channel, FileLock lock, ApiEngineConfig config)
    {
        this.directory = directory;
        this.file = directory.resolve("checkpoint.json");
        this.channel = channel;
        this.lock = lock;
        this.config = config;
    }

    static ApiSessionStore open(Path profile, String id, boolean resume, ApiEngineConfig config)
    {
        Path root = profile.resolve("api-sessions");
        Path directory = root.resolve(id);
        FileChannel channel = null;
        FileLock lock = null;
        try {
            if (!resume) {
                Files.createDirectories(profile);
                privateDirectory(root);
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            requireDirectory(root);
            requireDirectory(directory);
            Path lockPath = directory.resolve("writer.lock");
            channel = FileChannel.open(lockPath, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("writer is in use");
            }
            ApiSessionStore store = new ApiSessionStore(directory, channel, lock, config);
            if (resume) {
                if (!Files.isRegularFile(store.file, LinkOption.NOFOLLOW_LINKS) || Files.size(store.file) > config.maximumCheckpointBytes) {
                    throw new IOException("missing or oversized checkpoint");
                }
                byte[] bytes;
                try (var input = Files.newInputStream(store.file, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(config.maximumCheckpointBytes + 1);
                }
                if (bytes.length > config.maximumCheckpointBytes) {
                    throw new IOException("oversized checkpoint");
                }
                store.state = ApiCheckpointCodec.decode(bytes);
                store.validate(store.state, id);
            }
            else {
                store.save(ApiCheckpoint.initial(id, config.identity, config.lane));
            }
            return store;
        }
        catch (IOException | RuntimeException failure) {
            try {
                if (lock != null) {
                    lock.release();
                }
                if (channel != null) {
                    channel.close();
                }
            }
            catch (IOException cleanup) {
                throw new AgentUnavailableException("API session writer cleanup failed");
            }
            String message = failure instanceof OverlappingFileLockException ? "API session already has a writer" : "Cannot acquire API session checkpoint";
            throw new AgentUnavailableException(message);
        }
    }

    private void validate(ApiCheckpoint candidate, String id)
            throws IOException
    {
        if (!candidate.id().equals(id) || !candidate.identity().equals(config.identity) || !candidate.lane().equals(config.lane)) {
            throw new IOException("checkpoint ownership or version mismatch");
        }
    }

    @Override
    public synchronized ApiCheckpoint state() { return state; }

    @Override
    public synchronized void save(ApiCheckpoint next)
    {
        if (closed) {
            throw new AgentUnavailableException("API session writer is closed");
        }
        Path temporary = directory.resolve("checkpoint-" + UUID.randomUUID() + ".tmp");
        try {
            validate(next, directory.getFileName().toString());
            byte[] bytes = ApiCheckpointCodec.encode(next);
            if (bytes.length > config.maximumCheckpointBytes) {
                throw new IOException("checkpoint budget exceeded");
            }
            try (FileChannel output = FileChannel.open(temporary, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
                parent.force(true);
            }
            state = next;
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Cannot persist API session checkpoint");
        }
        finally {
            try {
                Files.deleteIfExists(temporary);
            }
            catch (IOException failure) {
                // The committed checkpoint remains authoritative; an uncommitted temporary file is never replayed.
            }
        }
    }

    @Override
    public ModelInput.UserMessage stage(String text, List<AgentAttachment> attachments)
    {
        List<AgentAttachment> staged = new ArrayList<>();
        long total = 0;
        List<Path> created = new ArrayList<>();
        try {
            Path folder = directory.resolve("attachments");
            if (!attachments.isEmpty()) {
                privateDirectory(folder);
            }
            for (AgentAttachment attachment : attachments) {
                if (!Files.isRegularFile(attachment.path(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("attachment is not a regular file");
                }
                Path target = folder.resolve(UUID.randomUUID().toString());
                created.add(target);
                try (var input = Files.newInputStream(attachment.path(), LinkOption.NOFOLLOW_LINKS);
                        FileChannel output = FileChannel.open(target, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                    byte[] bytes = new byte[8192];
                    int count;
                    while ((count = input.read(bytes)) != -1) {
                        total += count;
                        if (total > config.maximumAttachmentBytes) {
                            throw new IOException("attachment budget exceeded");
                        }
                        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
                        while (buffer.hasRemaining()) {
                            output.write(buffer);
                        }
                    }
                    output.force(true);
                }
                staged.add(new AgentAttachment(attachment.id(), attachment.name(), attachment.mediaType(), target));
            }
            if (!staged.isEmpty()) {
                try (FileChannel parent = FileChannel.open(folder, StandardOpenOption.READ)) {
                    parent.force(true);
                }
            }
            return new ModelInput.UserMessage(text, staged);
        }
        catch (IOException failure) {
            for (Path path : created) {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException cleanup) {
                    // Unreferenced attachments are never exposed to a model or used as conversation history.
                }
            }
            throw new AgentUnavailableException("Cannot stage API session attachments");
        }
    }

    private static void privateDirectory(Path path)
            throws IOException
    {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        requireDirectory(path);
    }

    private static void requireDirectory(Path path)
            throws IOException
    {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("session directory is missing or a symlink");
        }
    }

    @Override
    public synchronized void close()
    {
        if (!closed) {
            try {
                lock.release();
                channel.close();
                closed = true;
            }
            catch (IOException failure) {
                throw new AgentUnavailableException("Cannot release API session writer");
            }
        }
    }
}

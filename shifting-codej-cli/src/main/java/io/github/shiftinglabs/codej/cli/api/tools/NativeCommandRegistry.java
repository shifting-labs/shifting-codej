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
package io.github.shiftinglabs.codej.cli.api.tools;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

/** Private durable unit intents. File locks fence live backends; a stopped receipt never invents a tool result. */
final class NativeCommandRegistry
        implements AutoCloseable
{
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new Jdk8Module()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final Path owner;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    record State(int version, String id, String boot, String directory, String key, Optional<String> launch,
            List<String> controls, boolean attempted, boolean registered, String group, boolean released, boolean stopped)
    {
        State
        {
            requireNonNull(launch, "launch is null");
            controls = List.copyOf(controls);
            if (version != 1 || !uuid(id) || !uuid(boot) || !Path.of(directory).isAbsolute() || Path.of(directory).getNameCount() < 2
                    || !Path.of(directory).getFileName().toString().startsWith("codej-native-") || key.isBlank() || key.length() > 512
                    || controls.size() > 32 || controls.stream().anyMatch(value -> !identifier(value)) || launch.filter(value -> !identifier(value)).isPresent()
                    || group.length() > 4096 || registered && !attempted || released && (!registered || group.isEmpty())) {
                throw new IllegalArgumentException("Invalid native execution intent");
            }
        }

        String unit() { return "codej-command-" + id + ".service"; }

        Path root() { return Path.of(directory).resolve("root"); }

        @Override
        public String toString() { return "NativeCommandState[id=" + id + ", private]"; }
    }

    @FunctionalInterface
    interface Recovery
    {
        void recover(Entry entry)
                throws IOException;
    }

    static final class Entry
    {
        private final Path file;
        private State state;

        Entry(Path file, State state)
        {
            this.file = file;
            this.state = state;
        }

        synchronized State state() { return state; }

        synchronized void save(State next)
                throws IOException
        {
            byte[] bytes = JSON.writeValueAsBytes(next);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel out = FileChannel.open(temporary, java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                out.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sync(file.getParent());
            state = next;
        }

        synchronized void launch(String id, boolean attempted)
                throws IOException
        {
            State s = state;
            save(new State(1, s.id(), s.boot(), s.directory(), s.key(), Optional.of(id), s.controls(), attempted, s.registered(), s.group(), s.released(), s.stopped()));
        }

        synchronized void control(String id, boolean add)
                throws IOException
        {
            State s = state;
            var controls = new ArrayList<>(s.controls());
            if (add) {
                controls.add(id);
            }
            else {
                controls.remove(id);
            }
            save(new State(1, s.id(), s.boot(), s.directory(), s.key(), s.launch(), controls, s.attempted(), s.registered(), s.group(), s.released(), s.stopped()));
        }

        synchronized void registered(String group)
                throws IOException
        {
            State s = state;
            if (!s.group().isEmpty() && !group.isEmpty() && !s.group().equals(group)) {
                throw new IOException("Native control group identity changed");
            }
            // An inactive unit may omit ControlGroup; keep the earlier kernel identity for shutdown proof.
            String remembered = group.isEmpty() ? s.group() : group;
            save(new State(1, s.id(), s.boot(), s.directory(), s.key(), s.launch(), s.controls(), true, true, remembered, s.released(), s.stopped()));
        }

        synchronized void released()
                throws IOException
        {
            State s = state;
            save(new State(1, s.id(), s.boot(), s.directory(), s.key(), s.launch(), s.controls(), s.attempted(), true, s.group(), true, s.stopped()));
        }

        synchronized void stopped()
                throws IOException
        {
            State s = state;
            save(new State(1, s.id(), s.boot(), s.directory(), s.key(), s.launch(), s.controls(), s.attempted(), s.registered(), s.group(), s.released(), true));
        }

        synchronized void remove(int entries)
                throws IOException
        {
            if (!state.stopped()) {
                throw new IOException("Native execution is still owned");
            }
            cleanup(state, entries);
            Files.deleteIfExists(file);
            sync(file.getParent());
        }
    }

    NativeCommandRegistry(Path profile, Recovery recovery)
            throws IOException
    {
        Path base = profile.resolve("native-command-state");
        privateDirectory(base);
        int count = 0;
        try (var owners = Files.newDirectoryStream(base)) {
            for (Path previous : owners) {
                if (++count > 256 || !uuid(previous.getFileName().toString()) || !Files.isDirectory(previous, NOFOLLOW_LINKS)) {
                    throw new IOException("Native recovery journal requires reconciliation");
                }
                try (FileChannel old = FileChannel.open(previous.resolve("owner.lock"), StandardOpenOption.WRITE, NOFOLLOW_LINKS)) {
                    FileLock claimed;
                    try {
                        claimed = old.tryLock();
                    }
                    catch (OverlappingFileLockException busy) {
                        continue;
                    }
                    if (claimed == null) {
                        continue;
                    }
                    try (claimed; var entries = Files.newDirectoryStream(previous, "*.json")) {
                        int receipts = 0;
                        for (Path file : entries) {
                            if (++receipts > 32 || !uuid(file.getFileName().toString().replaceFirst("\\.json$", ""))) {
                                throw new IOException("Native recovery journal exceeds its bound");
                            }
                            State state = JSON.readValue(NativeUnit.read(file, 16384), State.class);
                            if (!file.getFileName().toString().equals(state.id() + ".json")) {
                                throw new IOException("Native recovery identity mismatch");
                            }
                            recovery.recover(new Entry(file, state));
                        }
                        if (receipts == 0) {
                            try (var files = Files.list(previous)) {
                                if (files.allMatch(path -> path.getFileName().toString().equals("owner.lock"))) {
                                    Files.delete(previous.resolve("owner.lock"));
                                    Files.delete(previous);
                                }
                            }
                        }
                    }
                }
            }
        }
        owner = base.resolve(UUID.randomUUID().toString());
        privateDirectory(owner);
        channel = FileChannel.open(owner.resolve("owner.lock"), java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        lock = channel.lock();
    }

    Entry create(String boot)
            throws IOException
    {
        String id = UUID.randomUUID().toString();
        Path directory = Files.createTempDirectory(Path.of("/tmp"), "codej-native-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        String key = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey().toString();
        var state = new State(1, id, boot, directory.toString(), key, Optional.empty(), List.of(), false, false, "", false, false);
        var entry = new Entry(owner.resolve(id + ".json"), state);
        try {
            entry.save(state);
        }
        catch (IOException failure) {
            try {
                Files.delete(directory);
            }
            catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return entry;
    }

    static void cleanup(State state, int entries)
            throws IOException
    {
        Path directory = Path.of(state.directory());
        if (!directory.getParent().equals(Path.of("/tmp").toRealPath())) {
            throw new IOException("Native artifact parent changed");
        }
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            var attrs = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || !state.key().equals(String.valueOf(attrs.fileKey()))) {
                throw new IOException("Native artifact identity changed");
            }
            delete(directory, 0, new int[1], entries);
        }
    }

    private static void delete(Path path, int depth, int[] visited, int maximum)
            throws IOException
    {
        if (depth > 128 || ++visited[0] > maximum) {
            throw new IOException("Native artifact cleanup exceeds its bound");
        }
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            try (var children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    delete(child, depth + 1, visited, maximum);
                }
            }
        }
        Files.delete(path);
    }

    static void privateDirectory(Path path)
            throws IOException
    {
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        catch (java.nio.file.FileAlreadyExistsException ignored) {
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.getOwner(path, NOFOLLOW_LINKS).equals(Files.getOwner(path.getParent()))) {
            throw new IOException("Native artifact directory is not private");
        }
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}"); }

    private static boolean uuid(String value) { return value != null && value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"); }

    private static void sync(Path directory)
            throws IOException
    {
        try (var file = FileChannel.open(directory, StandardOpenOption.READ, NOFOLLOW_LINKS)) {
            file.force(true);
        }
    }

    @Override
    public void close()
            throws IOException
    {
        if (!closed) {
            try (var files = Files.list(owner)) {
                if (files.allMatch(path -> path.getFileName().toString().equals("owner.lock"))) {
                    Files.delete(owner.resolve("owner.lock"));
                    Files.delete(owner);
                }
            }
            lock.release();
            channel.close();
            closed = true;
        }
    }
}

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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;

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
        implements AutoCloseable
{
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final Path directory;
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private final ApiEngineConfig config;
    private ObjectNode state;
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
                JsonNode parsed = JSON.readTree(bytes);
                if (!(parsed instanceof ObjectNode node)) {
                    throw new IOException("invalid checkpoint");
                }
                store.state = node;
                store.validate(id);
            }
            else {
                ObjectNode state = JSON.createObjectNode().put("version", 1).put("id", id)
                        .put("connector", config.identity.connectorId()).put("profile", config.identity.profileId()).put("lane", config.lane.value())
                        .put("phase", "ready");
                state.putArray("journal");
                state.putArray("receipts");
                store.save(state);
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

    private void validate(String id)
            throws IOException
    {
        if (state.path("version").asInt() != 1 || !state.path("id").asText().equals(id)
                || !state.path("connector").asText().equals(config.identity.connectorId())
                || !state.path("profile").asText().equals(config.identity.profileId()) || !state.path("lane").asText().equals(config.lane.value())
                || !Set.of("ready", "request", "tools", "blocked").contains(state.path("phase").asText())
                || !state.path("journal").isArray() || !state.path("receipts").isArray()) {
            throw new IOException("checkpoint ownership or version mismatch");
        }
        if (state.has("replay")) {
            replay(state);
        }
        for (JsonNode receipt : state.path("receipts")) {
            if (!receipt.path("id").isTextual() || !receipt.path("name").isTextual() || !receipt.path("arguments").isTextual()
                    || !receipt.path("started").isBoolean()) {
                throw new IOException("invalid tool receipt");
            }
            new io.github.shiftinglabs.codej.cli.spi.model.ModelResponse.ToolCall(
                    receipt.path("id").textValue(), receipt.path("name").textValue(), receipt.path("arguments").textValue());
            if (receipt.has("result") && (!receipt.path("result").path("text").isTextual() || !receipt.path("result").path("error").isBoolean())) {
                throw new IOException("invalid tool result");
            }
        }
        if (state.path("phase").asText().equals("tools") && (!state.has("replay") || state.path("receipts").isEmpty()
                || !state.path("setup").path("model").isTextual() || !state.path("setup").path("instructions").isTextual()
                || !state.path("setup").path("tools").isArray())) {
            throw new IOException("incomplete tool checkpoint");
        }
    }

    synchronized ObjectNode state() { return state.deepCopy(); }

    synchronized void save(ObjectNode next)
    {
        if (closed) {
            throw new AgentUnavailableException("API session writer is closed");
        }
        Path temporary = directory.resolve("checkpoint-" + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = JSON.writeValueAsBytes(next);
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
            state = next.deepCopy();
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

    ModelInput.UserMessage stage(String text, List<AgentAttachment> attachments)
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

    static void replay(ObjectNode node, ModelReplayState replay)
    {
        node.putObject("replay").put("provider", replay.providerId()).put("version", replay.formatVersion()).put("payload", replay.payload());
    }

    static ModelReplayState replay(ObjectNode node)
    {
        JsonNode replay = node.path("replay");
        if (!replay.path("provider").isTextual() || !replay.path("version").isIntegralNumber() || !replay.path("payload").isTextual()) {
            throw new AgentUnavailableException("Invalid API replay checkpoint");
        }
        return new ModelReplayState(replay.path("provider").textValue(), replay.path("version").intValue(), replay.path("payload").textValue());
    }

    static ObjectNode input(ModelInput input)
    {
        ObjectNode node = JSON.createObjectNode();
        if (input instanceof ModelInput.UserMessage user) {
            node.put("kind", "user").put("text", user.text());
            var attachments = node.putArray("attachments");
            user.attachments().forEach(item -> attachments.addObject().put("id", item.id()).put("name", item.name())
                    .put("mediaType", item.mediaType()).put("path", item.path().toString()));
        }
        else if (input instanceof ModelInput.ToolResults batch) {
            node.put("kind", "results");
            var results = node.putArray("results");
            batch.results().forEach(item -> results.addObject().put("id", item.callId()).put("text", item.text()).put("error", item.error()));
        }
        return node;
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

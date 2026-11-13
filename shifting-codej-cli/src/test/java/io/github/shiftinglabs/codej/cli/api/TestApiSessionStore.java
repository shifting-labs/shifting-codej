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
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiSessionStore
{
    @TempDir
    Path directory;

    @Test
    void writerAcquisitionLoadsAuthoritativePrivateCheckpointAndClosePreservesIt()
            throws Exception
    {
        String id = UUID.randomUUID().toString();
        ApiEngineConfig config = builder().build();
        try (ApiSessionStore first = ApiSessionStore.open(directory, id, false, config)) {
            var old = first.state();
            var next = first.state();
            ApiSessionStore.replay(next, new ModelReplayState("fake-api", 1, "signed opaque payload"));
            first.save(next);
            assertThat(old.has("replay")).isFalse();
            assertThatThrownBy(() -> ApiSessionStore.open(directory, id, true, config)).isInstanceOf(AgentUnavailableException.class);
            Path checkpoint = directory.resolve("api-sessions").resolve(id).resolve("checkpoint.json");
            assertThat(Files.getPosixFilePermissions(checkpoint)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(checkpoint.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
        try (ApiSessionStore resumed = ApiSessionStore.open(directory, id, true, config)) {
            assertThat(ApiSessionStore.replay(resumed.state()).payload()).isEqualTo("signed opaque payload");
            resumed.close();
            resumed.close();
            assertThatThrownBy(() -> resumed.save(resumed.state())).isInstanceOf(AgentUnavailableException.class);
        }
    }

    @Test
    void failedCheckpointWriteLeavesPreviousCommitReadable()
            throws Exception
    {
        String id = UUID.randomUUID().toString();
        ApiEngineConfig config = builder().maximumCheckpointBytes(512).build();
        String original;
        Path checkpoint = directory.resolve("api-sessions").resolve(id).resolve("checkpoint.json");
        try (ApiSessionStore store = ApiSessionStore.open(directory, id, false, config)) {
            original = Files.readString(checkpoint);
            var oversized = store.state();
            ApiSessionStore.replay(oversized, new ModelReplayState("fake-api", 1, "x".repeat(1024)));
            assertThatThrownBy(() -> store.save(oversized)).isInstanceOf(AgentUnavailableException.class);
            assertThat(Files.readString(checkpoint)).isEqualTo(original);
            assertThat(store.state().has("replay")).isFalse();
        }
        try (ApiSessionStore resumed = ApiSessionStore.open(directory, id, true, config)) {
            assertThat(resumed.state().has("replay")).isFalse();
        }
        try (var files = Files.list(checkpoint.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).doesNotContainAnyElementsOf(List.of("checkpoint.tmp"));
        }
    }

    @Test
    void foreignProfileAndMalformedVersionsReleaseTheirWriterOnFailure()
            throws Exception
    {
        String id = UUID.randomUUID().toString();
        ApiEngineConfig original = builder().build();
        try (ApiSessionStore store = ApiSessionStore.open(directory, id, false, original)) {
            assertThat(store.state().path("version").asInt()).isEqualTo(1);
        }
        ApiEngineConfig wrong = ApiEngineConfig.builder(new ConnectorIdentity("fake-api", "other"), new AgentLaneId("fake-api"), "model",
                () -> { throw new AssertionError("No client construction"); }).build();
        assertThatThrownBy(() -> ApiSessionStore.open(directory, id, true, wrong)).isInstanceOf(AgentUnavailableException.class);
        try (ApiSessionStore store = ApiSessionStore.open(directory, id, true, original)) {
            store.save(store.state().put("version", 2));
        }
        assertThatThrownBy(() -> ApiSessionStore.open(directory, id, true, original)).isInstanceOf(AgentUnavailableException.class);
        Path file = directory.resolve("api-sessions").resolve(id).resolve("checkpoint.json");
        var fixed = (com.fasterxml.jackson.databind.node.ObjectNode) ApiSessionStore.JSON.readTree(Files.readString(file));
        Files.writeString(file, ApiSessionStore.JSON.writeValueAsString(fixed.put("version", 1)));
        try (ApiSessionStore store = ApiSessionStore.open(directory, id, true, original)) {
            assertThat(store.state().path("version").asInt()).isEqualTo(1);
        }
    }

    @Test
    void symlinksMissingCheckpointsAndOversizedAttachmentsAreRejected()
            throws Exception
    {
        ApiEngineConfig config = builder().maximumAttachmentBytes(3).build();
        assertThatThrownBy(() -> ApiSessionStore.open(directory, UUID.randomUUID().toString(), true, config)).isInstanceOf(AgentUnavailableException.class);
        Path original = Files.writeString(directory.resolve("original"), "too large");
        Path link = Files.createSymbolicLink(directory.resolve("link"), original);
        String id = UUID.randomUUID().toString();
        try (ApiSessionStore store = ApiSessionStore.open(directory, id, false, config)) {
            for (Path path : List.of(original, link)) {
                assertThatThrownBy(() -> store.stage("Look", List.of(new AgentAttachment("image", "image/png", path))))
                        .isInstanceOf(AgentUnavailableException.class);
            }
            try (var staged = Files.list(directory.resolve("api-sessions").resolve(id).resolve("attachments"))) {
                assertThat(staged).isEmpty();
            }
        }
        Path checkpoint = directory.resolve("api-sessions").resolve(id).resolve("checkpoint.json");
        Files.delete(checkpoint);
        Files.createSymbolicLink(checkpoint, original);
        assertThatThrownBy(() -> ApiSessionStore.open(directory, id, true, config)).isInstanceOf(AgentUnavailableException.class);
        assertThat(original).hasContent("too large");
    }

    @Test
    void configurationRequiresPositiveBudgetsAndSnapshotsItsBuilder()
    {
        ApiEngineConfig.Builder builder = builder().maximumSessions(1).maximumRequests(2).maximumToolCalls(3).maximumOutputTokens(4)
                .maximumTokens(5).maximumTextChars(6).maximumToolOutputChars(7).maximumCheckpointBytes(512).maximumAttachmentBytes(9)
                .shutdownTimeout(Duration.ofMillis(10)).approveTools(false);
        ApiEngineConfig config = builder.build();
        builder.maximumSessions(2).maximumRequests(3);
        assertThat(config.maximumSessions).isEqualTo(1);
        assertThat(config.maximumRequests).isEqualTo(2);
        assertThat(config.maximumToolCalls).isEqualTo(3);
        assertThat(config.maximumOutputTokens).isEqualTo(4);
        assertThat(config.maximumTokens).isEqualTo(5);
        assertThat(config.maximumTextChars).isEqualTo(6);
        assertThat(config.maximumToolOutputChars).isEqualTo(7);
        assertThat(config.maximumCheckpointBytes).isEqualTo(512);
        assertThat(config.maximumAttachmentBytes).isEqualTo(9);
        assertThat(config.shutdownTimeout).isEqualTo(Duration.ofMillis(10));
        assertThat(config.approveTools).isFalse();
        for (java.util.function.Consumer<ApiEngineConfig.Builder> invalid : List.<java.util.function.Consumer<ApiEngineConfig.Builder>>of(
                b -> b.maximumSessions(0), b -> b.maximumRequests(0), b -> b.maximumToolCalls(0), b -> b.maximumOutputTokens(0),
                b -> b.maximumTokens(0), b -> b.maximumTextChars(0), b -> b.maximumToolOutputChars(0), b -> b.maximumCheckpointBytes(0),
                b -> b.maximumAttachmentBytes(0), b -> b.shutdownTimeout(Duration.ZERO), b -> b.shutdownTimeout(Duration.ofMinutes(2)))) {
            assertThatThrownBy(() -> invalid.accept(builder())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> builder().executor(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().redactor(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().shutdownTimeout(null)).isInstanceOf(NullPointerException.class);
    }

    private static ApiEngineConfig.Builder builder()
    {
        return ApiEngineConfig.builder(new ConnectorIdentity("fake-api", "work"), new AgentLaneId("fake-api"), "model",
                () -> { throw new AssertionError("Store tests never create a model client"); });
    }
}

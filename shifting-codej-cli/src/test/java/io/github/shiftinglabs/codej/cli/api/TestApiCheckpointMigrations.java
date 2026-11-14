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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiCheckpointMigrations
{
    private static final String ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path directory;

    @Test
    void currentFixtureRoundTripsWithoutMigrationOrDuplicatedHistory()
            throws Exception
    {
        byte[] original = fixture(2);
        ApiCheckpoint first = ApiCheckpointCodec.decode(original);
        ApiCheckpoint second = ApiCheckpointCodec.decode(ApiCheckpointCodec.encode(first));
        assertThat(second.version()).isEqualTo(ApiCheckpoint.CURRENT_VERSION);
        assertThat(second.history()).isEqualTo(first.history());
        assertThat(second.history().complete()).isTrue();
        assertThat(second.history().exchanges()).hasSize(1);
        assertThat(ApiCheckpointCodec.JSON.readTree(ApiCheckpointCodec.encode(second))).isEqualTo(ApiCheckpointCodec.JSON.readTree(original));
    }

    @ParameterizedTest
    @EnumSource(ApiCheckpoint.Phase.class)
    void legacyUpgradePreservesEveryPhaseAndUncertainToolReceipt(ApiCheckpoint.Phase phase)
            throws Exception
    {
        var old = (ObjectNode) ApiCheckpointCodec.JSON.readTree(fixture(1));
        old.put("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        ((ObjectNode) old.withArray("receipts").get(0)).remove("result");
        ApiCheckpoint upgraded = ApiCheckpointCodec.decode(ApiCheckpointCodec.JSON.writeValueAsBytes(old));
        assertThat(upgraded.phase()).isEqualTo(phase);
        assertThat(upgraded.recoverable()).isFalse();
        assertThat(upgraded.history().complete()).isFalse();
        assertThat(upgraded.history().exchanges()).isEmpty();
        assertThat(upgraded.receipts().getFirst().uncertain()).isTrue();
        assertThat(upgraded.replay().orElseThrow().payload()).isEqualTo("opaque signed replay");
        assertThat(upgraded.setup().orElseThrow().toolContracts()).isEmpty();
        var roundTrip = (ObjectNode) ApiCheckpointCodec.JSON.readTree(ApiCheckpointCodec.encode(upgraded));
        roundTrip.put("version", 1);
        roundTrip.remove("history");
        assertThat(roundTrip).isEqualTo(old);
    }

    @Test
    void pristineLegacyStateHasNoMissingPrefixAndUnsentUsersAreNotInventedHistory()
            throws Exception
    {
        ApiCheckpoint pristine = ApiCheckpoint.initial(ID, new ConnectorIdentity("fake-api", "work"), new AgentLaneId("fake-api-work"))
                .append(new ApiJournal.User("not-submitted", new ModelInput.UserMessage("Unsent", List.of())));
        var old = (ObjectNode) ApiCheckpointCodec.JSON.readTree(ApiCheckpointCodec.encode(pristine));
        old.put("version", 1);
        old.remove("history");
        ApiCheckpoint upgraded = ApiCheckpointCodec.decode(ApiCheckpointCodec.JSON.writeValueAsBytes(old));
        assertThat(upgraded.history().complete()).isTrue();
        assertThat(upgraded.history().exchanges()).isEmpty();
        assertThat(upgraded.journal()).containsExactlyElementsOf(pristine.journal());
    }

    @Test
    void repositoryMigratesInMemoryAndWritesOnlyAtAnExplicitDurableCommit()
            throws Exception
    {
        byte[] old = fixture(1);
        Path file = legacyFile(old);
        ApiEngineConfig config = config().build();
        try (ApiSessionStore store = ApiSessionStore.open(directory, ID, true, config)) {
            assertThat(store.state().version()).isEqualTo(ApiCheckpoint.CURRENT_VERSION);
            assertThat(Files.readAllBytes(file)).isEqualTo(old);
            assertThatThrownBy(() -> ApiSessionStore.open(directory, ID, true, config)).isInstanceOf(AgentUnavailableException.class);
            store.save(store.state());
            assertThat(ApiCheckpointCodec.JSON.readTree(Files.readAllBytes(file)).path("version").intValue()).isEqualTo(ApiCheckpoint.CURRENT_VERSION);
        }
        try (ApiSessionStore store = ApiSessionStore.open(directory, ID, true, config)) {
            assertThat(store.state().history().complete()).isFalse();
            assertThat(store.state().history().exchanges()).isEmpty();
            assertThat(store.state().replay().orElseThrow().payload()).isEqualTo("opaque signed replay");
        }
    }

    @Test
    void rejectedOwnerFutureFormatAndFailedMigrationCommitPreserveTheLegacyFileAndReleaseWriter()
            throws Exception
    {
        byte[] old = fixture(1);
        Path file = legacyFile(old);
        ApiEngineConfig foreign = ApiEngineConfig.builder(new ConnectorIdentity("fake-api", "other"), new AgentLaneId("fake-api-work"), "model",
                () -> { throw new AssertionError("No inference"); }).build();
        assertThatThrownBy(() -> ApiSessionStore.open(directory, ID, true, foreign)).isInstanceOf(AgentUnavailableException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(old);
        var future = (ObjectNode) ApiCheckpointCodec.JSON.readTree(old);
        future.put("version", 999);
        byte[] unknown = ApiCheckpointCodec.JSON.writeValueAsBytes(future);
        Files.write(file, unknown);
        assertThatThrownBy(() -> ApiSessionStore.open(directory, ID, true, config().build())).isInstanceOf(AgentUnavailableException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(unknown);
        Files.write(file, old);
        ApiEngineConfig bounded = config().maximumCheckpointBytes(old.length).build();
        try (ApiSessionStore store = ApiSessionStore.open(directory, ID, true, bounded)) {
            ApiCheckpoint previous = store.state();
            // The fixture is formatted, so add enough typed history-neutral journal data to exceed the budget.
            ApiCheckpoint larger = previous.append(new ApiJournal.User("unsent", new ModelInput.UserMessage("x".repeat(old.length), List.of())));
            assertThatThrownBy(() -> store.save(larger)).isInstanceOf(AgentUnavailableException.class);
            assertThat(store.state()).isSameAs(previous);
            assertThat(Files.readAllBytes(file)).isEqualTo(old);
        }
        try (ApiSessionStore store = ApiSessionStore.open(directory, ID, true, config().build())) {
            assertThat(store.state().history().complete()).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidHistory")
    void currentHistoryRejectsMalformedOrContradictoryState(String name, Consumer<ObjectNode> mutation)
            throws Exception
    {
        var wire = (ObjectNode) ApiCheckpointCodec.JSON.readTree(fixture(2));
        mutation.accept(wire);
        assertThatThrownBy(() -> ApiCheckpointCodec.decode(ApiCheckpointCodec.JSON.writeValueAsBytes(wire))).as(name).isInstanceOf(IOException.class);
    }

    static Stream<Arguments> invalidHistory()
    {
        return Stream.of(
                invalid("missing history", root -> root.remove("history")),
                invalid("null history", root -> root.putNull("history")),
                invalid("missing completeness", root -> root.withObject("history").remove("complete")),
                invalid("coerced completeness", root -> root.withObject("history").put("complete", "true")),
                invalid("null exchanges", root -> root.withObject("history").putNull("exchanges")),
                invalid("duplicate exchange", root -> root.withObject("history").withArray("exchanges").add(exchange(root).deepCopy())),
                invalid("missing input", root -> exchange(root).remove("input")),
                invalid("null assistant text", root -> exchange(root).putNull("text")),
                invalid("coerced assistant text", root -> exchange(root).put("text", 42)),
                invalid("unknown exchange field", root -> exchange(root).put("extra", true)),
                invalid("unknown stop reason", root -> exchange(root).put("stopReason", "FUTURE")),
                invalid("missing complete prefix", root -> root.withObject("history").withArray("exchanges").removeAll()),
                invalid("wrong settled request", root -> exchange(root).put("requestId", "invented")),
                invalid("wrong settled stop", root -> exchange(root).put("stopReason", "REFUSED")),
                invalid("wrong model provenance", root -> exchange(root).put("model", "different")),
                invalid("tool calls on completed response", root -> exchange(root).putArray("toolCalls").addObject()
                        .put("id", "call").put("name", "lookup").put("arguments", "{}")),
                invalid("tools phase disagrees with history", root -> {
                    root.put("phase", "tools");
                    root.withArray("receipts").addObject().put("id", "call").put("name", "lookup").put("arguments", "{}").put("started", false);
                }));
    }

    private static ObjectNode exchange(ObjectNode root) { return (ObjectNode) root.withObject("history").withArray("exchanges").get(0); }

    private static Arguments invalid(String name, Consumer<ObjectNode> change) { return Arguments.of(name, change); }

    private Path legacyFile(byte[] bytes)
            throws Exception
    {
        try (ApiSessionStore ignored = ApiSessionStore.open(directory, ID, false, config().build())) {
            Path file = directory.resolve("api-sessions").resolve(ID).resolve("checkpoint.json");
            Files.write(file, bytes);
            return file;
        }
    }

    private static ApiEngineConfig.Builder config()
    {
        return ApiEngineConfig.builder(new ConnectorIdentity("fake-api", "work"), new AgentLaneId("fake-api-work"), "model",
                () -> { throw new AssertionError("Migration never creates a client"); });
    }

    private static byte[] fixture(int version)
            throws IOException
    {
        try (var stream = TestApiCheckpointMigrations.class.getResourceAsStream("/api/checkpoint-v" + version + ".json")) {
            return requireNonNull(stream, "missing migration fixture").readAllBytes();
        }
    }
}

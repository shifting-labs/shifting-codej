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
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiCheckpointCodec
{
    @Test
    void versionOneFixtureRoundTripsEveryJournalAndInputKindWithoutChangingWireShape()
            throws Exception
    {
        byte[] original = fixture();
        ApiCheckpoint state = ApiCheckpointCodec.decode(original);
        assertThat(state.recoverable()).isTrue();
        assertThat(state.setup().orElseThrow().toolContracts()).isEmpty();
        assertThat(state.setup().orElseThrow().tools().getFirst().inputSchema()).isEqualTo(" {} ");
        assertThat(state.replay().orElseThrow().payload()).isEqualTo("opaque signed replay");
        assertThat(state.journal()).hasSize(7);
        assertThat(state.journal().getFirst()).isInstanceOf(ApiJournal.User.class);
        assertThat(((ApiJournal.User) state.journal().getFirst()).input().attachments().getFirst().name()).isEqualTo("image.png");
        assertThat(state.receipts().getFirst().result()).contains(new ToolRegistration.Result("Saved", false));
        assertThat(ApiCheckpointCodec.JSON.readTree(ApiCheckpointCodec.encode(state))).isEqualTo(ApiCheckpointCodec.JSON.readTree(original));
        assertThat(state.toString()).doesNotContain("opaque", "signed", "Hello", "Host instructions");
        assertThat(state.setup().orElseThrow().toString()).doesNotContain("Host instructions");
    }

    @Test
    void initialCheckpointOmitsOptionalFieldsAndDoesNotRequireNewMetadata()
            throws Exception
    {
        ApiCheckpoint original = initial();
        byte[] encoded = ApiCheckpointCodec.encode(original);
        assertThat(ApiCheckpointCodec.decode(encoded)).isEqualTo(original);
        assertThat(new String(encoded, StandardCharsets.UTF_8)).doesNotContain("replay", "setup", "input");
        for (ApiCheckpoint.Phase phase : List.of(ApiCheckpoint.Phase.READY, ApiCheckpoint.Phase.BLOCKED)) {
            assertThat(ApiCheckpointCodec.decode(ApiCheckpointCodec.encode(original.withPhase(phase))).phase()).isEqualTo(phase);
        }
    }

    @Test
    void transitionsKeepSnapshotsImmutableAndRequireReconciliationUntilAnObservedToolResult()
            throws Exception
    {
        var tools = new ArrayList<>(List.of(new ModelRequest.ToolDefinition("example", "Example", "{}")));
        var setup = new ApiRequestSetup("model", "instructions", Optional.empty(), Optional.empty(), tools, Optional.of(List.of()));
        tools.clear();
        ApiCheckpoint original = initial();
        ApiCheckpoint request = original.append(new ApiJournal.User("turn", new ModelInput.UserMessage("Hello", List.of())))
                .submitting("request-1", setup, new ModelInput.UserMessage("Hello", List.of()));
        assertThat(setup.tools()).hasSize(1);
        assertThatThrownBy(() -> setup.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(original.phase()).isEqualTo(ApiCheckpoint.Phase.READY);
        assertThat(original.journal()).isEmpty();
        assertThat(request.recoverable()).isFalse();
        assertThat(ApiCheckpointCodec.decode(ApiCheckpointCodec.encode(request)).input()).isEqualTo(request.input());
        var response = new ModelResponse("", List.of(new ModelResponse.ToolCall("call-1", "example", "{}")), ModelResponse.StopReason.TOOL_CALLS,
                new ModelReplayState("fake-api", 1, "private signature"), Optional.empty());
        ApiCheckpoint toolsPending = request.observed("request-1", response);
        ApiCheckpoint admitted = toolsPending.started(0);
        assertThat(toolsPending.recoverable()).isTrue();
        assertThat(toolsPending.receipts().getFirst().started()).isFalse();
        assertThat(admitted.recoverable()).isFalse();
        ApiCheckpoint completed = admitted.completed(0, "Saved", false);
        assertThat(completed.recoverable()).isTrue();
        assertThat(completed.receipts().getFirst().invocation()).isEqualTo(new ToolDispatcher.Call("call-1", "example", "{}", "request-1:call-1"));
        assertThat(admitted.receipts().getFirst().result()).isEmpty();
        assertThatThrownBy(() -> completed.receipts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> completed.journal().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedChanges")
    void malformedCheckpointsFailAtTheCodecBoundary(String name, Consumer<ObjectNode> change)
            throws Exception
    {
        var wire = (ObjectNode) ApiCheckpointCodec.JSON.readTree(fixture());
        change.accept(wire);
        assertThatThrownBy(() -> ApiCheckpointCodec.decode(ApiCheckpointCodec.JSON.writeValueAsBytes(wire)))
                .as(name).isInstanceOf(IOException.class);
    }

    static Stream<Arguments> malformedChanges()
    {
        return Stream.of(
                malformed("string version", root -> root.put("version", "1")),
                malformed("fractional version", root -> root.put("version", 1.5)),
                malformed("missing version", root -> root.remove("version")),
                malformed("null version", root -> root.putNull("version")),
                malformed("foreign version", root -> root.put("version", 2)),
                malformed("noncanonical id", root -> root.put("id", "../escape")),
                malformed("unknown phase", root -> root.put("phase", "TOOLS")),
                malformed("missing tools replay", root -> root.remove("replay")),
                malformed("null replay", root -> root.putNull("replay")),
                malformed("missing tools setup", root -> root.remove("setup")),
                malformed("null journal member", root -> root.withArray("journal").addNull()),
                malformed("unknown journal kind", root -> ((ObjectNode) root.withArray("journal").get(0)).put("kind", "future")),
                malformed("unknown journal field", root -> ((ObjectNode) root.withArray("journal").get(0)).put("extra", true)),
                malformed("missing admission flag", root -> ((ObjectNode) root.withArray("receipts").get(0)).remove("started")),
                malformed("coerced admission flag", root -> ((ObjectNode) root.withArray("receipts").get(0)).put("started", "true")),
                malformed("duplicate receipt", root -> root.withArray("receipts").add(root.withArray("receipts").get(0).deepCopy())),
                malformed("invalid tool arguments", root -> ((ObjectNode) root.withArray("receipts").get(0)).put("arguments", "[]")),
                malformed("null manifest", root -> root.withObject("setup").putNull("toolContracts")),
                malformed("null effort", root -> root.withObject("setup").putNull("effort")),
                malformed("null outcome", root -> ((ObjectNode) root.withArray("receipts").get(0)).putNull("result")),
                malformed("coerced tool metadata", root -> ((ObjectNode) root.withObject("setup").withArray("tools").get(0)).put("description", 42)),
                malformed("float tool metadata", root -> ((ObjectNode) root.withObject("setup").withArray("tools").get(0)).put("description", 42.5)),
                malformed("boolean tool metadata", root -> ((ObjectNode) root.withObject("setup").withArray("tools").get(0)).put("description", true)),
                malformed("unknown tool metadata", root -> ((ObjectNode) root.withObject("setup").withArray("tools").get(0)).put("extra", true)),
                malformed("invalid tool contract", root -> root.withObject("setup").putArray("toolContracts").addObject()
                        .put("name", "example").put("version", "1").put("schemaFingerprint", "a".repeat(64))),
                malformed("invalid replay version", root -> root.withObject("replay").put("version", 0)),
                malformed("missing outcome flag", root -> ((ObjectNode) root.withArray("receipts").get(0)).withObject("result").remove("error")));
    }

    @Test
    void duplicateKeysTrailingTokensAndNullDocumentsAreRejected()
            throws Exception
    {
        String valid = new String(ApiCheckpointCodec.encode(initial()), StandardCharsets.UTF_8);
        for (String corrupt : List.of(valid.replace("\"version\":1", "\"version\":1,\"version\":1"), valid + " {}", "null", "[]")) {
            assertThatThrownBy(() -> ApiCheckpointCodec.decode(corrupt.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> initial().withPhase(ApiCheckpoint.Phase.TOOLS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> initial().withPhase(ApiCheckpoint.Phase.REQUEST)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Arguments malformed(String name, Consumer<ObjectNode> change) { return Arguments.of(name, change); }

    private static ApiCheckpoint initial()
    {
        return ApiCheckpoint.initial("11111111-1111-4111-8111-111111111111", new ConnectorIdentity("fake-api", "work"), new AgentLaneId("fake-api-work"));
    }

    private static byte[] fixture()
            throws IOException
    {
        try (var input = TestApiCheckpointCodec.class.getResourceAsStream("/api/checkpoint-v1.json")) {
            return requireNonNull(input, "missing checkpoint fixture").readAllBytes();
        }
    }
}

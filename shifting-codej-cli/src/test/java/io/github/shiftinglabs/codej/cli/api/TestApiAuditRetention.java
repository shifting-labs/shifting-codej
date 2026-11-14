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

import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionMemory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetention;
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetentionPolicy;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiAuditRetention
{
    private static final ModelInput.UserMessage USER = new ModelInput.UserMessage("private old input", List.of());
    private static final ApiRequestSetup SETUP = new ApiRequestSetup("model", "instructions", Optional.empty(), Optional.empty(), List.of(), Optional.empty());
    private static final ModelReplayState REPLAY = new ModelReplayState("fake", 2, "private summary replay");
    private static final AuditRetention KEEP_ONE = new AuditRetention(1, 0);
    private static final Optional<ModelCompactionMemory> MEMORY = Optional.of(new ModelCompactionMemory("three", "summary"));

    @Test
    void pruningPreservesCompleteToolGroupsRecoveryFactsAndNativeReplay()
            throws Exception
    {
        ApiCheckpoint original = history();
        ApiCheckpoint retained = ApiAuditRetention.apply(original, MEMORY, KEEP_ONE, Optional.empty());
        assertThat(retained.version()).isEqualTo(3);
        assertThat(retained.history().complete()).isFalse();
        assertThat(retained.history().summarizedThroughRequestId()).contains("three");
        assertThat(retained.history().exchanges()).extracting(ModelHistory.Exchange::requestId).containsExactly("four");
        assertThat(retained.journal()).contains(new ApiJournal.CodingLaunch("launch"), new ApiJournal.PreparedResource("call", new ToolResource("process", "resource")));
        assertThat(retained.journal().stream().filter(ApiJournal.Response.class::isInstance)).hasSize(1);
        assertThat(retained.journal()).noneMatch(entry -> entry instanceof ApiJournal.ToolResult);
        assertThat(retained.replay()).isEqualTo(original.replay());
        assertThat(retained.setup()).isEqualTo(original.setup());
        assertThat(retained.receipts()).isEmpty();
        assertThat(original.history().complete()).isTrue();
        assertThat(original.history().exchanges()).hasSize(4);
        assertThat(ApiCheckpointCodec.encode(ApiCheckpointCodec.decode(ApiCheckpointCodec.encode(retained)))).isEqualTo(ApiCheckpointCodec.encode(retained));
        assertThat(ApiAuditRetention.apply(retained, MEMORY, KEEP_ONE, Optional.empty())).isSameAs(retained);
    }

    @Test
    void retainedExchangeCountIsASafeFloorAndFreshIntentCanPrecedeARecoveryResponse()
    {
        ApiCheckpoint original = history();
        // Asking to keep two would cut between the invocation and its results; keep the entire group.
        var kept = ApiAuditRetention.apply(original, MEMORY, new AuditRetention(2, 0), Optional.empty());
        assertThat(kept.history().exchanges()).hasSize(3);
        assertThat(kept.history().summarizedThroughRequestId()).contains("one");
        var protectedInput = new ModelInput.UserMessage("fresh unsent", List.of());
        var old = ApiCheckpoint.initial(original.id(), original.identity(), original.lane()).append(new ApiJournal.User("fresh", protectedInput));
        for (var exchange : original.history().exchanges()) {
            old = old.submitting(exchange.requestId(), SETUP, exchange.input()).observed(exchange.requestId(),
                    new ModelResponse(exchange.text(), exchange.toolCalls(), exchange.stopReason(), REPLAY, Optional.empty()));
        }
        var next = ApiAuditRetention.apply(old, MEMORY, KEEP_ONE, Optional.of(protectedInput));
        assertThat(next.journal()).contains(new ApiJournal.User("fresh", protectedInput));
    }

    @Test
    void uncertainOrUnknownHistoryAndUncoveredDataAreNotPruned()
    {
        ApiCheckpoint ready = history();
        assertThat(ApiAuditRetention.apply(ready, Optional.empty(), KEEP_ONE, Optional.empty())).isSameAs(ready);
        assertThat(ApiAuditRetention.apply(ready, MEMORY, new AuditRetention(10, 0), Optional.empty())).isSameAs(ready);
        for (var state : List.of(ready.withPhase(ApiCheckpoint.Phase.BLOCKED), ready.submitting("new", SETUP, USER))) {
            assertThat(ApiAuditRetention.apply(state, MEMORY, KEEP_ONE, Optional.empty())).isSameAs(state);
        }
        ApiCheckpoint pending = ready.submitting("new", SETUP, USER).observed("new", response(List.of(new ModelResponse.ToolCall("pending", "lookup", "{}"))));
        assertThat(ApiAuditRetention.apply(pending, MEMORY, KEEP_ONE, Optional.empty())).isSameAs(pending);
        var unknown = new ApiCheckpoint(2, ready.id(), ready.identity(), ready.lane(), ready.phase(), ready.journal(), ready.receipts(), ready.replay(),
                ready.setup(), ready.input(), new ModelHistory(false, ready.history().exchanges()));
        assertThat(ApiAuditRetention.apply(unknown, MEMORY, KEEP_ONE, Optional.empty())).isSameAs(unknown);
        assertThatThrownBy(() -> ApiAuditRetention.cursor(ready.history(), Optional.of(new ModelCompactionMemory("two", "summary"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiAuditRetention.cursor(ready.history(), Optional.of(new ModelCompactionMemory("missing", "summary"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroRetentionKeepsTheSummaryAnchorAndRejectsMalformedVersionMetadata()
            throws Exception
    {
        ApiCheckpoint ready = history();
        var memory = Optional.of(new ModelCompactionMemory("four", "summary"));
        var empty = ApiAuditRetention.apply(ready, memory, new AuditRetention(0, 0), Optional.empty());
        assertThat(empty.history().exchanges()).isEmpty();
        assertThat(empty.history().summarizedThroughRequestId()).contains("four");
        assertThat(empty.setup()).isEmpty();
        assertThat(empty.input()).isEmpty();
        assertThat(ApiAuditRetention.cursor(empty.history(), memory)).isZero();
        assertThatThrownBy(() -> ApiAuditRetention.cursor(empty.history(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThat(ApiCheckpointCodec.encode(ApiCheckpointCodec.decode(ApiCheckpointCodec.encode(empty)))).isEqualTo(ApiCheckpointCodec.encode(empty));
        String encoded = new String(ApiCheckpointCodec.encode(empty), StandardCharsets.UTF_8);
        for (String invalid : List.of(encoded.replace("\"version\":3", "\"version\":2"),
                encoded.replace("\"summarizedThroughRequestId\":\"four\"", "\"summarizedThroughRequestId\":null"),
                encoded.replace("\"summarizedThroughRequestId\":\"four\"", "\"summarizedThroughRequestId\":\" \""),
                encoded.replace("\"complete\":false", "\"complete\":true"))) {
            assertThatThrownBy(() -> ApiCheckpointCodec.decode(invalid.getBytes(StandardCharsets.UTF_8))).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void retentionPlansAreExplicitBoundedAndReusable()
    {
        var plan = new AuditRetention(8, 12);
        assertThat(plan.retainExchanges()).isEqualTo(8);
        assertThat(plan.maximumAttachmentDeletions()).isEqualTo(12);
        assertThat(AuditRetentionPolicy.keepLatest(8, 12).retain(null)).contains(plan);
        assertThatThrownBy(() -> new AuditRetention(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRetention(0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRetention(0, 1025)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ApiCheckpoint history()
    {
        var state = ApiCheckpoint.initial("11111111-1111-4111-8111-111111111111", new ConnectorIdentity("fake", "work"), new AgentLaneId("fake"));
        state = state.append(new ApiJournal.User("old", USER)).submitting("one", SETUP, USER).observed("one", response(List.of()));
        state = state.append(new ApiJournal.CodingLaunch("launch"));
        state = state.submitting("two", SETUP, USER).observed("two", response(List.of(new ModelResponse.ToolCall("call", "lookup", "{}"))));
        state = state.append(new ApiJournal.PreparedResource("call", new ToolResource("process", "resource")));
        state = state.append(new ApiJournal.ToolResult("call", "private tool output", false));
        state = state.submitting("three", SETUP, new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call", "result", false)))).observed("three", response(List.of()));
        return state.append(new ApiJournal.User("recent", USER)).submitting("four", SETUP, USER).observed("four", response(List.of()));
    }

    private static ModelResponse response(List<ModelResponse.ToolCall> calls)
    {
        return new ModelResponse("answer", calls, calls.isEmpty() ? ModelResponse.StopReason.COMPLETED : ModelResponse.StopReason.TOOL_CALLS, REPLAY, Optional.empty());
    }
}

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
package io.github.shiftinglabs.codej.cli.spi.model;

import io.github.shiftinglabs.codej.cli.spi.extension.EngineObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestModelCompaction
{
    private static final ModelReplayState REPLAY = new ModelReplayState("fixture", 1, "private replay");

    @Test
    void proposalsAndRequestsAreBoundedTypedAndRedacted()
    {
        ModelCompaction proposal = new ModelCompaction("private summary", 0);
        ModelHistory history = new ModelHistory(true, List.of(exchange("one", new ModelInput.UserMessage("private input", List.of()), List.of())));
        var request = new ModelCompactionRequest("model", history, REPLAY, proposal, Optional.of(Instant.MAX));
        assertThat(proposal.summary()).isEqualTo("private summary");
        assertThat(proposal.retainExchanges()).isZero();
        assertThat(request.history()).isSameAs(history);
        assertThat(request.replay()).isSameAs(REPLAY);
        assertThat(request.compaction()).isSameAs(proposal);
        assertThat(request.model()).isEqualTo("model");
        assertThat(request.deadline()).contains(Instant.MAX);
        assertThat(proposal.toString()).doesNotContain("private summary");
        assertThat(request.toString()).doesNotContain("private input", "private replay", "private summary");
        assertThatThrownBy(() -> new ModelCompaction(null, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelCompaction(" ", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompaction("x".repeat(ModelCompaction.MAX_SUMMARY_CHARACTERS + 1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompaction("summary", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compactionRejectsPartialEmptyAndForeignHistoriesAndMissingFields()
    {
        ModelHistory history = new ModelHistory(true, List.of(exchange("one", new ModelInput.UserMessage("input", List.of()), List.of())));
        var proposal = new ModelCompaction("summary", 0);
        ModelCompactionRequest.validateBoundary(history, REPLAY, 0);
        assertThatThrownBy(() -> ModelCompactionRequest.validateBoundary(null, REPLAY, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ModelCompactionRequest.validateBoundary(history, null, 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ModelCompactionRequest.validateBoundary(history, REPLAY, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", new ModelHistory(false, history.exchanges()), REPLAY, proposal, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", new ModelHistory(true, List.of()), REPLAY, proposal, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", history, new ModelReplayState("foreign", 1, "replay"), proposal, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", history, REPLAY, new ModelCompaction("summary", 1), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest(" ", history, REPLAY, proposal, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", null, REPLAY, proposal, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", history, null, proposal, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", history, REPLAY, null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelCompactionRequest("model", history, REPLAY, proposal, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aToolChainMustBeKeptOrSummarizedAsACompleteGroup()
    {
        var call = new ModelResponse.ToolCall("call", "lookup", "{}");
        var user = new ModelInput.UserMessage("input", List.of());
        var tool = new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call", "value", false)));
        var prefix = exchange("one", user, List.of());
        var invoking = exchange("two", user, List.of(call));
        var resolved = exchange("three", tool, List.of());
        ModelHistory pending = new ModelHistory(true, List.of(prefix, invoking));
        assertThatThrownBy(() -> new ModelCompactionRequest("model", pending, REPLAY, new ModelCompaction("summary", 0), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        ModelHistory settled = new ModelHistory(true, List.of(prefix, invoking, resolved));
        assertThatThrownBy(() -> new ModelCompactionRequest("model", settled, REPLAY, new ModelCompaction("summary", 1), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ModelCompactionRequest("model", settled, REPLAY, new ModelCompaction("summary", 2), Optional.empty()).history()).isSameAs(settled);
        assertThat(new ModelCompactionRequest("model", settled, REPLAY, new ModelCompaction("summary", 0), Optional.empty()).history()).isSameAs(settled);
    }

    @Test
    void compactionObservationsExposeOnlyValidCounts()
    {
        var event = new EngineObservation.ContextCompacted("turn", 2, 3);
        assertThat(event.turnId()).isEqualTo("turn");
        assertThat(event.summarizedExchanges()).isEqualTo(2);
        assertThat(event.retainedExchanges()).isEqualTo(3);
        assertThatThrownBy(() -> new EngineObservation.ContextCompacted(" ", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineObservation.ContextCompacted("turn", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineObservation.ContextCompacted("turn", 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ModelHistory.Exchange exchange(String id, ModelInput input, List<ModelResponse.ToolCall> calls)
    {
        return new ModelHistory.Exchange(id, "fixture", "model", input, "answer", calls,
                calls.isEmpty() ? ModelResponse.StopReason.COMPLETED : ModelResponse.StopReason.TOOL_CALLS);
    }
}

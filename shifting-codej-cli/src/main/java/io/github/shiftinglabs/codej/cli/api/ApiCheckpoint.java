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
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable committed snapshot. Changes remain local until the repository's durable save succeeds. */
record ApiCheckpoint(int version, String id, ConnectorIdentity identity, AgentLaneId lane, Phase phase,
        List<ApiJournal.Entry> journal, List<ApiToolReceipt> receipts, Optional<ModelReplayState> replay,
        Optional<ApiRequestSetup> setup, Optional<ModelInput> input, ModelHistory history)
{
    static final int CURRENT_VERSION = 2;

    enum Phase { READY, REQUEST, TOOLS, BLOCKED }

    ApiCheckpoint
    {
        checkArgument(version == CURRENT_VERSION, "unsupported checkpoint version");
        checkArgument(UUID.fromString(id).toString().equals(id), "noncanonical session id");
        requireNonNull(identity, "identity is null");
        requireNonNull(lane, "lane is null");
        requireNonNull(phase, "phase is null");
        journal = List.copyOf(journal);
        receipts = List.copyOf(receipts);
        requireNonNull(replay, "replay is null");
        requireNonNull(setup, "setup is null");
        requireNonNull(input, "input is null");
        requireNonNull(history, "history is null");
        checkArgument(receipts.stream().map(ApiToolReceipt::id).distinct().count() == receipts.size(), "duplicate tool receipt");
        checkArgument(phase != Phase.TOOLS || (replay.isPresent() && setup.isPresent() && !receipts.isEmpty()), "incomplete tool checkpoint");
        checkArgument(phase != Phase.REQUEST || (setup.isPresent() && input.isPresent()), "incomplete request checkpoint");
        if (phase == Phase.REQUEST) {
            history.validateNext(input.orElseThrow());
        }
        if (!history.exchanges().isEmpty()) {
            List<ModelResponse.ToolCall> pending = history.exchanges().getLast().toolCalls();
            checkArgument(phase != Phase.READY || pending.isEmpty(), "ready history has unanswered tool calls");
            checkArgument(phase != Phase.TOOLS || pending.equals(receipts.stream()
                    .map(receipt -> new ModelResponse.ToolCall(receipt.id(), receipt.name(), receipt.arguments())).toList()), "history and tool receipts disagree");
        }
    }

    static ApiCheckpoint initial(String id, ConnectorIdentity identity, AgentLaneId lane)
    {
        return new ApiCheckpoint(CURRENT_VERSION, id, identity, lane, Phase.READY, List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                new ModelHistory(true, List.of()));
    }

    boolean recoverable() { return phase != Phase.REQUEST && phase != Phase.BLOCKED && receipts.stream().noneMatch(ApiToolReceipt::uncertain); }

    ApiCheckpoint withPhase(Phase value) { return copy(value, journal, receipts, replay, setup, input); }

    ApiCheckpoint withReplay(ModelReplayState value) { return copy(phase, journal, receipts, Optional.of(value), setup, input); }

    ApiCheckpoint append(ApiJournal.Entry entry)
    {
        List<ApiJournal.Entry> entries = new ArrayList<>(journal);
        entries.add(requireNonNull(entry, "entry is null"));
        return copy(phase, entries, receipts, replay, setup, input);
    }

    ApiCheckpoint submitting(String requestId, ApiRequestSetup envelope, ModelInput next)
    {
        return copy(Phase.REQUEST, journal, receipts, replay, Optional.of(envelope), Optional.of(next)).append(new ApiJournal.Request(requestId));
    }

    ApiCheckpoint observed(String requestId, ModelResponse response)
    {
        checkArgument(phase == Phase.REQUEST, "a response requires a submitted request");
        checkArgument(journal.getLast() instanceof ApiJournal.Request request && request.id().equals(requestId), "response request ID mismatch");
        ModelHistory nextHistory = history.append(new ModelHistory.Exchange(requestId, response.replayState().providerId(), setup.orElseThrow().model(),
                input.orElseThrow(), response.text(), response.toolCalls(), response.stopReason()));
        List<ApiJournal.Entry> entries = new ArrayList<>(journal);
        entries.add(new ApiJournal.Response(requestId, response.stopReason()));
        return new ApiCheckpoint(version, id, identity, lane, response.stopReason() == ModelResponse.StopReason.TOOL_CALLS ? Phase.TOOLS : Phase.READY, entries,
                response.toolCalls().stream().map(call -> ApiToolReceipt.pending(requestId, call)).toList(),
                Optional.of(response.replayState()), setup, input, nextHistory);
    }

    ApiCheckpoint started(int index)
    {
        ApiToolReceipt receipt = receipts.get(index);
        return replace(index, receipt.markStarted()).append(new ApiJournal.ToolStarted(receipt.id()));
    }

    ApiCheckpoint completed(int index, String text, boolean error)
    {
        ApiToolReceipt receipt = receipts.get(index);
        return replace(index, receipt.completed(text, error)).append(new ApiJournal.ToolResult(receipt.id(), text, error));
    }

    private ApiCheckpoint replace(int index, ApiToolReceipt receipt)
    {
        List<ApiToolReceipt> values = new ArrayList<>(receipts);
        values.set(index, receipt);
        return copy(phase, journal, values, replay, setup, input);
    }

    private ApiCheckpoint copy(Phase nextPhase, List<ApiJournal.Entry> entries, List<ApiToolReceipt> calls, Optional<ModelReplayState> nextReplay,
            Optional<ApiRequestSetup> nextSetup, Optional<ModelInput> nextInput)
    {
        return new ApiCheckpoint(version, id, identity, lane, nextPhase, entries, calls, nextReplay, nextSetup, nextInput, history);
    }

    @Override
    public String toString() { return "ApiCheckpoint[version=" + version + ", phase=" + phase + ", redacted]"; }
}

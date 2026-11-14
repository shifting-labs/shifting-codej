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

import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionMemory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetention;

import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;

/** Pure prefix removal; only committed summary coverage can replace discarded audit facts. */
final class ApiAuditRetention
{
    private ApiAuditRetention() {}

    static int cursor(ModelHistory history, Optional<ModelCompactionMemory> memory)
    {
        checkArgument(history.summarizedThroughRequestId().isEmpty() || memory.isPresent(), "retained history requires summary memory");
        if (memory.isEmpty()) {
            return 0;
        }
        String through = memory.orElseThrow().throughRequestId();
        if (history.summarizedThroughRequestId().filter(through::equals).isPresent()) {
            return 0;
        }
        int index = IntStream.range(0, history.exchanges().size()).filter(i -> history.exchanges().get(i).requestId().equals(through))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Summary boundary is absent from history")) + 1;
        checkArgument(safeBoundary(history, index), "summary boundary splits a tool group");
        return index;
    }

    private static boolean safeBoundary(ModelHistory history, int index)
    {
        var values = history.exchanges();
        return values.get(index - 1).toolCalls().isEmpty() && (index == values.size() || values.get(index).input() instanceof ModelInput.UserMessage);
    }

    static ApiCheckpoint apply(ApiCheckpoint state, Optional<ModelCompactionMemory> memory, AuditRetention plan, Optional<ModelInput.UserMessage> protectedInput)
    {
        if (state.phase() != ApiCheckpoint.Phase.READY || !state.recoverable() || !state.receipts().isEmpty()
                || memory.isEmpty() || !state.history().complete() && state.history().summarizedThroughRequestId().isEmpty()) {
            return state;
        }
        ModelHistory history = state.history();
        int cut = Math.max(0, Math.min(cursor(history, memory), history.exchanges().size() - plan.retainExchanges()));
        while (cut > 0 && !safeBoundary(history, cut)) {
            cut--;
        }
        if (cut == 0) {
            return state;
        }
        String boundary = history.exchanges().get(cut - 1).requestId();
        int journalCut = IntStream.range(0, state.journal().size()).filter(index -> state.journal().get(index) instanceof ApiJournal.Response response
                && response.id().equals(boundary)).findFirst().orElseThrow(() -> new IllegalArgumentException("Retention response is absent from journal")) + 1;
        int protectedUser = -1;
        for (int index = 0; index < state.journal().size(); index++) {
            if (state.journal().get(index) instanceof ApiJournal.User user && protectedInput.filter(user.input()::equals).isPresent()) {
                protectedUser = index;
            }
        }
        var journal = new ArrayList<ApiJournal.Entry>();
        for (int index = 0; index < state.journal().size(); index++) {
            ApiJournal.Entry entry = state.journal().get(index);
            if (index >= journalCut || index == protectedUser || entry instanceof ApiJournal.CodingLaunch || entry instanceof ApiJournal.PreparedResource) {
                journal.add(entry);
            }
        }
        ModelHistory retained = ModelHistory.retained(boundary, history.exchanges().subList(cut, history.exchanges().size()));
        boolean empty = retained.exchanges().isEmpty();
        return new ApiCheckpoint(ApiCheckpoint.RETENTION_VERSION, state.id(), state.identity(), state.lane(), state.phase(), journal,
                state.receipts(), state.replay(), empty ? Optional.empty() : state.setup(), empty ? Optional.empty() : state.input(), retained);
    }
}

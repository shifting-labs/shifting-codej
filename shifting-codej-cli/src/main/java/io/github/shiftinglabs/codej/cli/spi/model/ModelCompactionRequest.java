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

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Local replay rewrite at a settled boundary; the canonical history is immutable and remains complete. */
public record ModelCompactionRequest(String model, ModelHistory history, ModelReplayState replay,
        ModelCompaction compaction, Optional<Instant> deadline)
{
    public ModelCompactionRequest
    {
        checkText(model, "model");
        requireNonNull(history, "history is null");
        requireNonNull(replay, "replay is null");
        requireNonNull(compaction, "compaction is null");
        requireNonNull(deadline, "deadline is null");
        validateBoundary(history, replay, compaction.retainExchanges());
    }

    /** Validate a summary boundary before generating its text or spending an inference budget. */
    public static void validateBoundary(ModelHistory history, ModelReplayState replay, int retainExchanges)
    {
        requireNonNull(history, "history is null");
        requireNonNull(replay, "replay is null");
        checkArgument(retainExchanges >= 0, "retained exchanges must not be negative");
        var exchanges = history.exchanges();
        checkArgument(history.complete(), "compaction requires complete history");
        checkArgument(exchanges.stream().allMatch(exchange -> exchange.providerId().equals(replay.providerId())), "compaction cannot mix provider history");
        checkArgument(retainExchanges < exchanges.size(), "compaction must summarize a nonempty prefix");
        checkArgument(exchanges.getLast().toolCalls().isEmpty(), "cannot compact a pending tool batch");
        int boundary = exchanges.size() - retainExchanges;
        checkArgument(exchanges.get(boundary - 1).toolCalls().isEmpty(), "compaction cannot split a tool batch");
        checkArgument(boundary == exchanges.size() || exchanges.get(boundary).input() instanceof ModelInput.UserMessage,
                "retained context must start with a complete user exchange");
    }

    @Override
    public String toString() { return "ModelCompactionRequest[redacted]"; }
}

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
package io.github.shiftinglabs.codej.cli.spi.tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.Objects.requireNonNull;

/** Shared handles for synchronous tools whose work has actually stopped. Creates no workers or threads. */
public final class ToolExecutions
{
    private ToolExecutions() {}

    public static <R> ToolExecution<R> completed(R value) { return finished(ToolOutcome.completed(value)); }

    /**
     * Wraps an already quiescent invocation. The outcome may still be uncertain; stopped work does
     * not prove whether its effect happened. Never use this for a merely cancelled future.
     */
    public static <R> ToolExecution<R> finished(ToolOutcome<R> outcome)
    {
        requireNonNull(outcome, "outcome is null");
        CompletionStage<ToolOutcome<R>> completion = CompletableFuture.completedStage(outcome);
        CompletionStage<ToolStopResult> stopped = CompletableFuture.completedStage(ToolStopResult.verifiedStopped());
        return new ToolExecution<>()
        {
            @Override
            public CompletionStage<ToolOutcome<R>> completion() { return completion; }

            @Override
            public void cancel() {}

            @Override
            public CompletionStage<ToolStopResult> stopAndVerify() { return stopped; }
        };
    }
}

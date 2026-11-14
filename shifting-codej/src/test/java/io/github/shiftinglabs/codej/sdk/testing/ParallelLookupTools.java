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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.base.Preconditions.checkArgument;

/** An async in-memory lookup demonstrates overlap without threads or opaque host capabilities. */
public final class ParallelLookupTools
        implements ToolProvider<Boolean>
{
    public record Lookup(String key)
    {
        public Lookup { checkArgument(Set.of("first", "second").contains(key), "unknown lookup key"); }
    }

    public final List<String> settled = new CopyOnWriteArrayList<>();
    private static final ToolDefinition<Lookup, String> DEFINITION = ToolDefinitions.of(ToolDescriptor.builder("contract_lookup", "Lookup").build(),
            RecordToolParameters.of(Lookup.class), ToolOutput::text);

    @Override
    public String id() { return "parallel-lookup"; }

    @Override
    public List<ToolDefinition<?, ?>> definitions(Boolean configuration) { return List.of(DEFINITION); }

    @Override
    public ToolSet open(Boolean configuration, ToolSetContext context)
    {
        CompletableFuture<ToolOutcome<String>> first = new CompletableFuture<>();
        AgentTool<Lookup, String> tool = new AgentTool<>()
        {
            @Override
            public ToolDefinition<Lookup, String> definition() { return DEFINITION; }

            @Override
            public boolean parallelSafe() { return configuration; }

            @Override
            public ToolRequirements requirements(Lookup input, ToolPreparationContext preparation) { return ToolRequirements.none(); }

            @Override
            public ToolExecution<String> execute(Lookup input, ToolExecutionContext execution)
            {
                boolean second = input.key().equals("second");
                CompletableFuture<ToolOutcome<String>> outcome = second ? CompletableFuture.completedFuture(ToolOutcome.completed("second value")) : first;
                return new ToolExecution<>()
                {
                    @Override
                    public CompletionStage<ToolOutcome<String>> completion() { return outcome.minimalCompletionStage(); }

                    @Override
                    public void cancel() { outcome.complete(ToolOutcome.cancelled(new ToolFailure("CANCELLED", "Stopped"))); }

                    @Override
                    public CompletionStage<ToolStopResult> stopAndVerify()
                    {
                        return CompletableFuture.completedStage(outcome.isDone() ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Pending"));
                    }

                    @Override
                    public void committed()
                    {
                        settled.add(input.key());
                        if (second) {
                            first.complete(ToolOutcome.completed("first value"));
                        }
                    }
                };
            }
        };
        return new ToolSet()
        {
            @Override
            public List<AgentTool<?, ?>> tools() { return List.of(tool); }

            @Override
            public void close() {}
        };
    }
}

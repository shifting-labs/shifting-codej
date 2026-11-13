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
package io.github.shiftinglabs.codej.cli.api.tools;

import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.JsonSchemaParameters;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Adapts the existing opaque Tool lifecycle. Schema checks precede approval; its original record
 * binding and callback still run inside Tool.call. Borrowed executors and transport limits are retained.
 */
public final class LegacyToolAdapter
        implements AgentTool<LegacyToolAdapter.Arguments, String>
{
    private static final ToolRequirements REQUIREMENTS = ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();
    private final Tool original;
    private final ToolDefinition<Arguments, String> definition;
    private final Optional<Executor> executor;
    private final Function<Throwable, ToolOutcome<String>> failures;
    private final Supplier<ToolStopResult> verifier;
    private final Runnable committed;

    private LegacyToolAdapter(Builder builder)
    {
        original = builder.tool;
        executor = builder.executor;
        failures = builder.failures;
        verifier = builder.verifier;
        committed = builder.committed;
        definition = ToolDefinitions.of(ToolDescriptor.builder(original.name(), original.description(), Integer.MAX_VALUE).build(),
                JsonSchemaParameters.of(original.inputSchema(), input -> new Arguments(input.json())), RawOutput::new);
    }

    public static Builder builder(Tool tool) { return new Builder(tool); }

    @Override
    public ToolDefinition<Arguments, String> definition() { return definition; }

    @Override
    public ToolRequirements requirements(Arguments arguments, ToolPreparationContext context) { return REQUIREMENTS; }

    @Override
    public ToolExecution<String> execute(Arguments arguments, ToolExecutionContext context)
    {
        requireNonNull(arguments, "arguments is null");
        requireNonNull(context, "context is null");
        if (!context.grants().permits(REQUIREMENTS)) {
            throw new SecurityException("Host invocation is not granted");
        }
        Invocation invocation = new Invocation(arguments, context);
        if (executor.isPresent()) {
            try {
                executor.orElseThrow().execute(invocation::runDeferred);
            }
            catch (RuntimeException failure) {
                invocation.rejectBeforeStart(failure);
            }
        }
        else {
            invocation.runInline();
        }
        return invocation;
    }

    public record Arguments(String json)
    {
        public Arguments { requireNonNull(json, "json is null"); }

        @Override
        public String toString() { return "LegacyArguments[redacted]"; }
    }

    private record RawOutput(String text)
            implements ToolOutput
    {
        RawOutput { requireNonNull(text, "text is null"); }

        @Override
        public String mediaType() { return "text/plain"; }

        @Override
        public String toString() { return "LegacyOutput[redacted]"; }
    }

    public static final class Builder
    {
        private final Tool tool;
        private Optional<Executor> executor = Optional.empty();
        private Function<Throwable, ToolOutcome<String>> failures = failure -> ToolOutcome.failed(new ToolFailure("TOOL_FAILED", "Host tool failed"));
        private Supplier<ToolStopResult> verifier = ToolStopResult::verifiedStopped;
        private Runnable committed = () -> {};

        private Builder(Tool tool) { this.tool = requireNonNull(tool, "tool is null"); }

        public Builder executor(Executor value)
        {
            executor = Optional.of(requireNonNull(value, "executor is null"));
            return this;
        }

        public Builder failures(Function<Throwable, ToolOutcome<String>> value)
        {
            failures = requireNonNull(value, "failures is null");
            return this;
        }

        public Builder stopVerifier(Supplier<ToolStopResult> value)
        {
            verifier = requireNonNull(value, "stopVerifier is null");
            return this;
        }

        public Builder committed(Runnable value)
        {
            committed = requireNonNull(value, "committed is null");
            return this;
        }

        public LegacyToolAdapter build() { return new LegacyToolAdapter(this); }
    }

    private final class Invocation
            implements ToolExecution<String>
    {
        private final Arguments arguments;
        private final ToolExecutionContext context;
        private final CompletableFuture<ToolOutcome<String>> completion = new CompletableFuture<>();
        private boolean started;
        private boolean finished;
        private boolean acknowledged;

        Invocation(Arguments arguments, ToolExecutionContext context)
        {
            this.arguments = arguments;
            this.context = context;
        }

        private synchronized boolean begin()
        {
            if (started || finished) {
                return false;
            }
            started = true;
            return true;
        }

        void runInline()
        {
            if (begin()) {
                try {
                    context.checkActive();
                    finish(ToolOutcome.completed(requireNonNull(original.call(arguments.json()), "tool returned null")));
                }
                catch (Exception failure) {
                    finish(failureOutcome(failure));
                }
                // An inline Error retains the old incomplete-receipt behavior; close can still verify callback quiescence.
                finally {
                    markFinished();
                }
            }
        }

        void runDeferred()
        {
            if (begin()) {
                try {
                    context.checkActive();
                    finish(ToolOutcome.completed(requireNonNull(original.call(arguments.json()), "tool returned null")));
                }
                catch (Throwable failure) {
                    reject(new ExecutionException(failure));
                }
                finally {
                    markFinished();
                }
            }
        }

        private ToolOutcome<String> failureOutcome(Throwable failure)
        {
            ToolOutcome<String> outcome = requireNonNull(failures.apply(failure), "failure mapper returned null");
            Throwable cause = failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
            return cause instanceof java.util.concurrent.CancellationException && outcome.state() == ToolOutcome.State.FAILED
                    ? ToolOutcome.cancelled(outcome.failure().orElseThrow()) : outcome;
        }

        void rejectBeforeStart(Throwable failure)
        {
            synchronized (this) {
                // An executor may accept work and then throw. Never report that running callback stopped.
                if (started || finished) {
                    return;
                }
                finished = true;
            }
            reject(failure);
        }

        void reject(Throwable failure)
        {
            try {
                finish(failureOutcome(failure));
            }
            catch (Throwable mappingFailure) {
                markFinished();
                completion.completeExceptionally(mappingFailure);
            }
        }

        private synchronized void markFinished() { finished = true; }

        private void finish(ToolOutcome<String> outcome)
        {
            markFinished();
            completion.complete(outcome);
        }

        @Override
        public CompletionStage<ToolOutcome<String>> completion() { return completion.minimalCompletionStage(); }

        @Override
        public void cancel()
        {
            synchronized (this) {
                if (started || finished) {
                    return;
                }
                finished = true;
            }
            reject(new ExecutionException(new java.util.concurrent.CancellationException("Host tool cancelled before invocation")));
        }

        @Override
        public CompletionStage<ToolStopResult> stopAndVerify()
        {
            synchronized (this) {
                if (!finished) {
                    return CompletableFuture.completedStage(ToolStopResult.unverified("Host callback has not stopped"));
                }
            }
            return CompletableFuture.completedStage(requireNonNull(verifier.get(), "stop verifier returned null"));
        }

        @Override
        public synchronized void committed()
        {
            if (!acknowledged) {
                committed.run();
                acknowledged = true;
            }
        }
    }
}

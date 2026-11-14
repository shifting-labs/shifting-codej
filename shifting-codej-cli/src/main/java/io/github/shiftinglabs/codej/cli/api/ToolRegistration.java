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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.JsonSchemaParameters;
import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolOutput;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import io.github.shiftinglabs.codej.tool.ToolResultCodec;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Captures codecs, metadata and execution policy once; dispatch never guesses policy from names/classes. */
final class ToolRegistration
{
    record Contract(String name, int version, String schemaFingerprint)
    {
        Contract
        {
            requireNonNull(name, "name is null");
            if (version < 1 || !requireNonNull(schemaFingerprint, "schemaFingerprint is null").matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid tool contract");
            }
        }
    }

    record Result(String text, boolean error)
    {
        Result { requireNonNull(text, "text is null"); }

        @Override
        public String toString() { return "ToolResult[error=" + error + ", redacted]"; }
    }

    interface Prepared
    {
        ToolRequirements requirements();

        Active start(ToolExecutionContext context);
    }

    interface Active
    {
        Result await()
                throws InterruptedException, ExecutionException;

        void cancel();

        java.util.concurrent.CompletionStage<ToolStopResult> stopAndVerify();

        void committed();
    }

    @FunctionalInterface
    private interface Preparation
    {
        Prepared prepare(ToolArguments arguments, ToolPreparationContext context);
    }

    final ModelRequest.ToolDefinition definition;
    final Contract contract;
    final boolean legacy;
    final boolean approval;
    final Optional<Tool> original;
    private final Preparation preparation;
    private final Function<ToolFailure, String> failures;

    private ToolRegistration(ModelRequest.ToolDefinition definition, Contract contract, boolean legacy, boolean approval,
            Optional<Tool> original, Preparation preparation, Function<ToolFailure, String> failures)
    {
        this.definition = definition;
        this.contract = contract;
        this.legacy = legacy;
        this.approval = approval;
        this.original = original;
        this.preparation = preparation;
        this.failures = requireNonNull(failures, "failures is null");
    }

    static <P, R> ToolRegistration create(AgentTool<P, R> tool, boolean legacy, boolean approval, Optional<Tool> original,
            Function<ToolFailure, String> failures)
    {
        ToolDefinition<P, R> definition = requireNonNull(tool.definition(), "definition is null");
        var descriptor = requireNonNull(definition.descriptor(), "descriptor is null");
        ToolParameters<P> declared = requireNonNull(definition.parameters(), "parameters is null");
        var schema = requireNonNull(declared.schema(), "schema is null");
        // Legacy adapters already perform this same compiled check inside their parameter codec.
        ToolParameters<P> parameters = legacy ? declared : JsonSchemaParameters.of(schema.json(), declared::decode);
        ToolResultCodec<R> results = requireNonNull(definition.results(), "result codec is null");
        return new ToolRegistration(new ModelRequest.ToolDefinition(descriptor.name(), descriptor.description(), schema.json()),
                new Contract(descriptor.name(), descriptor.contractVersion(), schema.fingerprint()), legacy, approval, original,
                (arguments, context) -> {
                    P input = requireNonNull(parameters.decode(arguments), "parameter codec returned null");
                    ToolRequirements requirements = requireNonNull(tool.requirements(input, context), "requirements are null");
                    return new Prepared()
                    {
                        @Override
                        public ToolRequirements requirements() { return requirements; }

                        @Override
                        public Active start(ToolExecutionContext executionContext)
                        {
                            ToolExecution<R> execution = requireNonNull(tool.execute(input, executionContext), "tool returned no execution");
                            CompletableFuture<ToolOutcome<R>> completion = execution.completion().toCompletableFuture();
                            return new Active()
                            {
                                @Override
                                public Result await()
                                        throws InterruptedException, ExecutionException
                                {
                                    ToolOutcome<R> outcome = requireNonNull(completion.get(), "tool returned no outcome");
                                    if (outcome.state() == ToolOutcome.State.UNCERTAIN) {
                                        throw new ToolDispatcher.Uncertain(outcome.failure().orElseThrow().message());
                                    }
                                    if (outcome.state() != ToolOutcome.State.COMPLETED) {
                                        return new Result(failures.apply(outcome.failure().orElseThrow()), true);
                                    }
                                    ToolOutput output;
                                    try {
                                        output = requireNonNull(results.encode(outcome.value().orElseThrow()), "result codec returned null");
                                        if (!java.util.Set.of("text/plain", "application/json").contains(output.mediaType())) {
                                            throw new IllegalArgumentException("Unsupported tool output type");
                                        }
                                        return new Result(requireNonNull(output.text(), "output is null"), false);
                                    }
                                    catch (RuntimeException failure) {
                                        return new Result(failures.apply(new ToolFailure("RESULT_ENCODING_FAILED", "Tool ran but its result could not be encoded")), true);
                                    }
                                }

                                @Override
                                public void cancel() { execution.cancel(); }

                                @Override
                                public java.util.concurrent.CompletionStage<ToolStopResult> stopAndVerify() { return execution.stopAndVerify(); }

                                @Override
                                public void committed() { execution.committed(); }
                            };
                        }
                    };
                }, failures);
    }

    record Catalog(ModelRequest.ToolDefinition wire, Contract contract)
    {
        Catalog
        {
            requireNonNull(wire, "wire is null");
            requireNonNull(contract, "contract is null");
        }
    }

    static Catalog catalog(ToolDefinition<?, ?> definition)
    {
        requireNonNull(definition, "definition is null");
        var descriptor = requireNonNull(definition.descriptor(), "descriptor is null");
        var parameters = requireNonNull(definition.parameters(), "parameters is null");
        var schema = requireNonNull(parameters.schema(), "schema is null");
        requireNonNull(definition.results(), "result codec is null");
        JsonSchemaParameters.of(schema.json(), ToolArguments::json);
        return new Catalog(new ModelRequest.ToolDefinition(descriptor.name(), descriptor.description(), schema.json()),
                new Contract(descriptor.name(), descriptor.contractVersion(), schema.fingerprint()));
    }

    Catalog catalog() { return new Catalog(definition, contract); }

    String rejection(ToolDispatcher.Context context, String code, String message)
    {
        return legacy ? context.rejection(code, message) : failures.apply(new ToolFailure(code,
                code.equals("INVALID_ARGUMENTS") ? "Invalid tool arguments" : message));
    }

    static String failureText(ToolFailure failure)
    {
        try {
            return ApiSessionStore.JSON.writeValueAsString(failure);
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot encode tool failure", impossible);
        }
    }

    Prepared prepare(String arguments, ToolPreparationContext context) { return preparation.prepare(() -> arguments, context); }
}

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutions;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;

import java.io.IOException;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/** Shared codecs and authority checks; resource implementations retain their own containment and limits. */
final class CodingToolSupport
{
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());

    private CodingToolSupport() {}

    static <P extends Record, R> ToolDefinition<P, R> definition(String name, String description, Class<P> parameters)
    {
        // Reflection is one optional codec implementation, used here to preserve existing wire schemas.
        return ToolDefinitions.of(ToolDescriptor.builder(name, description).build(), RecordToolParameters.of(parameters), CodingToolSupport::encode);
    }

    private static ToolOutput encode(Object result)
    {
        try {
            return new JsonOutput(JSON.writeValueAsString(requireNonNull(result, "result is null")));
        }
        catch (JsonProcessingException failure) {
            throw new CodingToolException("FILE_IO", "The operation could not access the requested workspace path");
        }
    }

    private record JsonOutput(String text)
            implements ToolOutput
    {
        JsonOutput { requireNonNull(text, "text is null"); }

        @Override
        public String mediaType() { return "application/json"; }

        @Override
        public String toString() { return "CodingToolOutput[redacted]"; }
    }

    static ToolRequirements read(String path) { return ToolRequirements.builder().read(Path.of(path)).build(); }

    static ToolRequirements write(String path) { return ToolRequirements.builder().write(Path.of(path)).build(); }

    static ToolRequirements command()
    {
        // A command may read anywhere inside the contained workspace, not just its working directory.
        return ToolRequirements.builder().read(Path.of(".")).require(ToolCapability.PROCESS_EXECUTE).build();
    }

    static void check(ToolRequirements requirements, ToolExecutionContext context)
    {
        requireNonNull(context, "context is null");
        if (!context.grants().permits(requirements)) {
            throw new SecurityException("Coding tool requirements are not granted");
        }
        context.checkActive();
    }

    @FunctionalInterface
    interface Operation<R>
    {
        R run()
                throws IOException;
    }

    static <R> ToolExecution<R> execute(ToolRequirements requirements, ToolExecutionContext context, Operation<R> operation)
    {
        check(requirements, context);
        ToolOutcome<R> outcome;
        try {
            outcome = ToolOutcome.completed(operation.run());
        }
        catch (CodingToolException failure) {
            outcome = failure(failure);
        }
        catch (IOException failure) {
            outcome = ioFailure();
        }
        return ToolExecutions.finished(outcome);
    }

    static <R> ToolOutcome<R> failure(CodingToolException failure)
    {
        ToolFailure value = new ToolFailure(failure.failure().code(), failure.failure().message());
        return failure.uncertain() ? ToolOutcome.uncertain(value) : ToolOutcome.failed(value);
    }

    static <R> ToolOutcome<R> ioFailure()
    {
        return ToolOutcome.failed(new ToolFailure("FILE_IO", "The operation could not access the requested workspace path"));
    }
}

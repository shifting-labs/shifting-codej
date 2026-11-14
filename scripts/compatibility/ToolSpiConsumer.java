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

import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.api.tools.LegacyToolAdapter;
import io.github.shiftinglabs.codej.cli.api.tools.ReadFileTool;
import io.github.shiftinglabs.codej.cli.api.tools.ToolMessages;
import io.github.shiftinglabs.codej.cli.api.tools.WorkspaceReader;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutions;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import io.github.shiftinglabs.codej.tool.ToolSchema;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/** External consumer of the new interfaces; only the candidate jar and JDK are on its classpath. */
public final class ToolSpiConsumer
{
    public static void main(String[] args)
            throws Exception
    {
        ToolProvider<Configuration> provider = new GreetingProvider();
        var configuration = new Configuration("hello");
        var definitions = provider.definitions(configuration);
        require(provider.id().equals("greeting"), "Provider identity changed");
        require(definitions.getFirst().parameters().schema().fingerprint().length() == 64, "Schema fingerprint failed");
        try (ToolSet tools = provider.open(configuration, new AssemblyContext())) {
            require(run(tools.tools().getFirst(), ToolArguments.of("{ } ")).equals("hello"), "Typed tool round trip failed");
        }
        require(ToolSchema.of("{\"additionalProperties\":false,\"type\":\"object\"}")
                .equals(ToolSchema.of("{\"type\":\"object\",\"additionalProperties\":false}")), "Canonical schema differs");
        var records = RecordToolParameters.of(Count.class);
        Tool oldFacade = Tool.withParameters("count", "Returns a count", records, input -> Integer.toString(input.count()));
        require(oldFacade.forSubagents().subagents(), "Legacy subagent flag changed");
        var adapter = LegacyToolAdapter.builder(oldFacade).build();
        require(run(adapter, ToolArguments.of("{\"count\":2}"), ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build()).equals("2"),
                "Legacy adapter round trip failed");
        require(Tool.of("count", "Returns a count", Count.class, input -> "ok").inputSchema().equals(oldFacade.inputSchema()), "Record schema changed");
        verifyCodingHandler();
        System.out.println("PASS: Class-free SPI, codecs, legacy facade and built-in handler with an alternate workspace resource; JDK + jar only");
    }

    private static void verifyCodingHandler()
            throws Exception
    {
        WorkspaceReader reader = new WorkspaceReader()
        {
            @Override
            public ToolMessages.FileContent read(ToolMessages.ReadFile request, ToolExecutionContext context)
            {
                require(request.path().equals("note"), "Request binding changed");
                require(context.grants().permits(ToolRequirements.builder().read(Path.of("note")).build()), "Read authority missing");
                return new ToolMessages.FileContent("note", "a".repeat(64), "alternate backend", 1, false);
            }

            @Override
            public ToolMessages.FileListing list(ToolMessages.ListFiles request, ToolExecutionContext context) { throw new AssertionError("Unused operation"); }

            @Override
            public ToolMessages.SearchResult search(ToolMessages.SearchFiles request, ToolExecutionContext context) { throw new AssertionError("Unused operation"); }
        };
        var handler = new ReadFileTool(reader);
        String result = run(handler, ToolArguments.of("{\"path\":\"note\"}"), ToolRequirements.builder().read(Path.of("note")).build());
        require(result.contains("alternate backend"), "Handler/resource round trip failed");
        require(handler.definition().descriptor().name().equals("read_file"), "Built-in name changed");
    }

    private static <P, R> String run(AgentTool<P, R> tool, ToolArguments arguments)
            throws Exception
    {
        return run(tool, arguments, ToolRequirements.none());
    }

    private static <P, R> String run(AgentTool<P, R> tool, ToolArguments arguments, ToolRequirements grants)
            throws Exception
    {
        P parameters = tool.definition().parameters().decode(arguments);
        var context = new InvocationContext(grants);
        require(context.grants().permits(tool.requirements(parameters, context)), "Unexpected authority requirement");
        var execution = tool.execute(parameters, context);
        ToolOutcome<R> outcome = execution.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        execution.cancel();
        require(outcome.state() == ToolOutcome.State.COMPLETED, "Tool failed");
        require(execution.stopAndVerify().toCompletableFuture().get(1, TimeUnit.SECONDS).stopped(), "Shutdown is uncertain");
        String text = tool.definition().results().encode(outcome.value().orElseThrow()).text();
        execution.committed();
        return text;
    }

    private record Count(int count) {}

    private interface Input { boolean requested(); }

    private record Request(boolean requested) implements Input {}

    private record Result(String greeting)
    {
        private Result { requireNonNull(greeting, "greeting is null"); }
    }

    private record Configuration(String greeting)
    {
        private Configuration { requireNonNull(greeting, "greeting is null"); }
    }

    private static final class EmptyParameters
            implements ToolParameters<Input>
    {
        private final ToolSchema schema = ToolSchema.of("{\"type\":\"object\",\"additionalProperties\":false}");

        @Override
        public ToolSchema schema() { return schema; }

        @Override
        public Input decode(ToolArguments arguments)
        {
            require(arguments.json().strip().matches("\\{\\s*}"), "Expected empty arguments");
            return new Request(true);
        }
    }

    private static final class GreetingTool
            implements AgentTool<Input, Result>
    {
        private final String greeting;
        private final ToolDefinition<Input, Result> definition = ToolDefinitions.of(
                ToolDescriptor.builder("greet", "Returns the configured greeting").build(), new EmptyParameters(), result -> ToolOutput.text(result.greeting()));

        GreetingTool(String greeting) { this.greeting = greeting; }

        @Override
        public ToolDefinition<Input, Result> definition() { return definition; }

        @Override
        public ToolRequirements requirements(Input parameters, ToolPreparationContext context) { return ToolRequirements.none(); }

        @Override
        public ToolExecution<Result> execute(Input parameters, ToolExecutionContext context)
        {
            context.checkActive();
            require(parameters.requested(), "Unexpected input");
            return ToolExecutions.completed(new Result(greeting));
        }
    }

    private static final class GreetingProvider
            implements ToolProvider<Configuration>
    {
        @Override
        public String id() { return "greeting"; }

        @Override
        public List<ToolDefinition<?, ?>> definitions(Configuration configuration) { return List.of(new GreetingTool(configuration.greeting()).definition()); }

        @Override
        public ToolSet open(Configuration configuration, ToolSetContext context)
        {
            AgentTool<?, ?> tool = new GreetingTool(configuration.greeting());
            return new ToolSet()
            {
                private boolean closed;

                @Override
                public List<AgentTool<?, ?>> tools()
                {
                    require(!closed, "Tool set is closed");
                    return List.of(tool);
                }

                @Override
                public void close() { closed = true; }
            };
        }
    }

    private static final class InvocationContext
            implements ToolExecutionContext
    {
        private final ToolRequirements grants;

        InvocationContext(ToolRequirements grants) { this.grants = requireNonNull(grants, "grants is null"); }

        @Override
        public String invocationId() { return "example-1"; }

        @Override
        public Optional<Instant> deadline() { return Optional.empty(); }

        @Override
        public ToolRequirements grants() { return grants; }

        @Override
        public boolean cancellationRequested() { return false; }

        @Override
        public void recordPrepared(ToolResource resource) { throw new AssertionError("Greeting owns no external execution resource"); }
    }

    private static final class AssemblyContext
            implements ToolSetContext
    {
        @Override
        public AgentSessionContext session() { throw new AssertionError("Greeting needs no session resources"); }

        @Override
        public ConnectorContext connector() { throw new AssertionError("Greeting needs no connector resources"); }

        @Override
        public Executor executor() { return Runnable::run; }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

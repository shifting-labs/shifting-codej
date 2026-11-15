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
package io.github.shiftinglabs.codej.tool.testing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutions;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Fake application binding used by the reusable contracts; opens no files, processes, threads or network. */
public final class ToolContractFixtures
{
    public static final ToolSchema SCHEMA = ToolSchema.of("""
            {"type":"object","properties":{"key":{"type":"string"}},"required":["key"],"additionalProperties":false}
            """);
    public static final ToolFailure FAILURE = new ToolFailure("LOOKUP_FAILED", "Lookup failed");
    public static final ToolRequirements REQUIREMENTS = ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();

    private ToolContractFixtures() {}

    public interface LookupInput { String key(); }

    public record LookupRequest(String key)
            implements LookupInput
    {
        public LookupRequest { validate(key); }
    }

    public record GeneratedLookupRequest(String key)
            implements LookupInput
    {
        public GeneratedLookupRequest { validate(key); }
    }

    public record LookupResult(String key, String label)
    {
        public LookupResult
        {
            validate(key);
            requireNonNull(label, "label is null");
        }
    }

    private static void validate(String key)
    {
        checkArgument(requireNonNull(key, "key is null").matches("ISSUE-[0-9]{1,8}"), "Invalid issue key");
    }

    /** Class tokens are private to this optional record codec, not part of the parameter SPI. */
    public static final class RecordParameters
            implements ToolParameters<LookupInput>
    {
        private static final ObjectReader READER = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readerFor(LookupRequest.class);

        @Override
        public ToolSchema schema() { return SCHEMA; }

        @Override
        public LookupInput decode(ToolArguments arguments)
        {
            try {
                LookupRequest value = READER.readValue(arguments.json());
                checkArgument(value != null, "Expected lookup object");
                return value;
            }
            catch (IOException | IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid lookup arguments");
            }
        }
    }

    /** A different binder and DTO representation of the same public contract, without reflection. */
    public static final class StreamingParameters
            implements ToolParameters<LookupInput>
    {
        private static final JsonFactory JSON = new JsonFactory();

        @Override
        public ToolSchema schema() { return SCHEMA; }

        @Override
        public LookupInput decode(ToolArguments arguments)
        {
            try (var parser = JSON.createParser(arguments.json())) {
                checkArgument(parser.nextToken() == JsonToken.START_OBJECT, "Expected object");
                checkArgument(parser.nextToken() == JsonToken.FIELD_NAME && parser.currentName().equals("key"), "Expected key");
                checkArgument(parser.nextToken() == JsonToken.VALUE_STRING, "Expected string");
                String key = parser.getText();
                checkArgument(parser.nextToken() == JsonToken.END_OBJECT && parser.nextToken() == null, "Unexpected argument");
                return new GeneratedLookupRequest(key);
            }
            catch (IOException | IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid lookup arguments");
            }
        }
    }

    public interface LookupBackend { LookupResult lookup(LookupInput input); }

    public static final class Fixture
    {
        public final AtomicInteger calls = new AtomicInteger();
        public final AtomicBoolean fail = new AtomicBoolean();
        public final AgentTool<LookupInput, LookupResult> tool;

        public Fixture(boolean alternate)
        {
            ToolParameters<LookupInput> parameters = alternate ? new StreamingParameters() : new RecordParameters();
            LookupBackend implementation = alternate
                    ? input -> new LookupResult(input.key(), String.join("", "Issue ", input.key()))
                    : input -> new LookupResult(input.key(), "Issue " + input.key());
            LookupBackend backend = input -> {
                calls.incrementAndGet();
                if (fail.get()) {
                    throw new IllegalStateException("private backend diagnostic");
                }
                return implementation.lookup(input);
            };
            ToolDefinition<LookupInput, LookupResult> definition = ToolDefinitions.of(
                    ToolDescriptor.builder("contract_lookup", "Look up an issue").implementationRevision(alternate ? "alternate" : "reference").build(),
                    parameters, result -> ToolOutput.text(result.label()));
            tool = new AgentTool<>()
            {
                @Override
                public ToolDefinition<LookupInput, LookupResult> definition() { return definition; }

                @Override
                public ToolRequirements requirements(LookupInput input, ToolPreparationContext context)
                {
                    requireNonNull(context.invocationId(), "invocationId is null");
                    validate(input.key());
                    return REQUIREMENTS;
                }

                @Override
                public ToolExecution<LookupResult> execute(LookupInput input, ToolExecutionContext context)
                {
                    context.checkActive();
                    if (!context.grants().permits(requirements(input, context))) {
                        throw new SecurityException("Lookup capability is not granted");
                    }
                    context.recordPrepared(new ToolResource("fake-lookup", context.invocationId()));
                    try {
                        LookupResult result = backend.lookup(input);
                        context.progress("Lookup completed");
                        return ToolExecutions.completed(result);
                    }
                    catch (IllegalStateException failure) {
                        return ToolExecutions.finished(ToolOutcome.failed(FAILURE));
                    }
                }
            };
        }
    }

    public static final class Context
            implements ToolExecutionContext
    {
        public final List<ToolResource> prepared = new ArrayList<>();
        public final List<String> progress = new ArrayList<>();
        public ToolRequirements grants = REQUIREMENTS;
        public Optional<Instant> deadline = Optional.empty();
        public boolean cancelled;
        public boolean failJournal;

        @Override
        public String invocationId() { return "invocation-1"; }

        @Override
        public Optional<Instant> deadline() { return deadline; }

        @Override
        public ToolRequirements grants() { return grants; }

        @Override
        public boolean cancellationRequested() { return cancelled; }

        @Override
        public void recordPrepared(ToolResource resource)
        {
            if (failJournal) {
                throw new IllegalStateException("Journal unavailable");
            }
            prepared.add(resource);
        }

        @Override
        public void progress(String message)
        {
            ToolExecutionContext.super.progress(message);
            progress.add(message);
        }
    }

    public record Configuration(boolean alternate, boolean duplicate) {}

    public static final class Provider
            implements ToolProvider<Configuration>
    {
        public final AtomicInteger opened = new AtomicInteger();
        public final AtomicInteger closed = new AtomicInteger();
        public boolean failClose;

        @Override
        public String id() { return "fake-tools"; }

        @Override
        public List<ToolDefinition<?, ?>> definitions(Configuration config) { return List.of(new Fixture(config.alternate()).tool.definition()); }

        @Override
        public ToolSet open(Configuration config, ToolSetContext context)
        {
            requireNonNull(context.executor(), "executor is null");
            opened.incrementAndGet();
            try {
                Fixture fixture = new Fixture(config.alternate());
                if (config.duplicate()) {
                    throw new IllegalArgumentException("Duplicate tool name");
                }
                return new ToolSet()
                {
                    private boolean released;

                    @Override
                    public List<AgentTool<?, ?>> tools()
                    {
                        if (released) {
                            throw new IllegalStateException("Tool set is closed");
                        }
                        return List.of(fixture.tool);
                    }

                    @Override
                    public void close()
                    {
                        if (!released) {
                            if (failClose) {
                                throw new IllegalStateException("Work has not stopped");
                            }
                            closed.incrementAndGet();
                            released = true;
                        }
                    }
                };
            }
            catch (RuntimeException failure) {
                closed.incrementAndGet();
                throw failure;
            }
        }
    }

    public static final class SetContext
            implements ToolSetContext
    {
        public final NoProcesses supervisor = new NoProcesses();
        private final Path root;

        public SetContext(Path root) { this.root = root; }

        @Override
        public AgentSessionContext session()
        {
            return new AgentSessionContext("task", root, root.resolve("runtime"), root.resolve("runtime/provider"), AgentPermissionPolicy.AUTO,
                    Optional.empty(), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), Optional.empty(), Optional.empty(), SessionAccess.TOOLS_ONLY, "");
        }

        @Override
        public ConnectorContext connector() { return new ConnectorContext(root.resolve("profile"), supervisor, ToolServer.DEFAULT); }

        @Override
        public Executor executor() { return Runnable::run; }
    }

    public static final class NoProcesses
            implements ProcessSupervisor, AutoCloseable
    {
        public boolean closed;

        @Override
        public PreparedProcess prepare(ProcessSpec spec) { throw new AssertionError("No process in tool SPI tests"); }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses() { throw new AssertionError("No recovery in tool SPI tests"); }

        @Override
        public OwnedProcessRecovery recoverOwnedProcess(String id) { throw new AssertionError("No recovery in tool SPI tests"); }

        @Override
        public boolean launchedHere(String id) { throw new AssertionError("No process query in tool SPI tests"); }

        @Override
        public void acknowledge(String id) { throw new AssertionError("No process acknowledgement in tool SPI tests"); }

        @Override
        public void close() { closed = true; }
    }
}

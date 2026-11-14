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

import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProviderBinding;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import io.github.shiftinglabs.codej.tool.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiToolProviders
{
    @TempDir
    Path directory;

    record Input(int count)
    {
        Input
        {
            if (count < 1) {
                throw new IllegalArgumentException("private domain diagnostic");
            }
        }
    }

    record Settings(String name, int version, String prefix) {}

    private static final Settings SETTINGS = new Settings("sample", 1, "value");
    private static final ToolRequirements HOST = ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();

    private final class Provider
            implements ToolProvider<Settings>
    {
        int described;
        int opened;
        int closed;
        int executed;
        int bound;
        int cancelled;
        int committed;
        boolean failOpen;
        boolean errorOpen;
        boolean nullIntent;
        boolean schedule;
        boolean failInspection;
        boolean refuseClose;
        boolean mismatch;
        boolean unsupportedSchema;
        boolean pending;
        boolean uncertain;
        boolean verified = true;
        boolean throwDuringExecution;
        ToolRequirements requirements = HOST;
        ToolFailure failure;
        ToolSetContext assembly;
        ToolExecutionContext invocation;
        AgentSessionRef reference;
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<ToolOutcome<String>> completion = new CompletableFuture<>();
        final List<String> trace = new ArrayList<>();

        @Override
        public String id() { return "sample-provider"; }

        private ToolDefinition<Input, String> definition(Settings settings)
        {
            var records = RecordToolParameters.of(Input.class);
            ToolParameters<Input> parameters = new ToolParameters<>()
            {
                @Override
                public ToolSchema schema()
                {
                    return unsupportedSchema ? ToolSchema.of("{\"minimum\":1}", value -> value) : records.schema();
                }

                @Override
                public Input decode(ToolArguments arguments)
                {
                    bound++;
                    return records.decode(arguments);
                }
            };
            return ToolDefinitions.of(ToolDescriptor.builder(settings.name(), "Configured sample").contractVersion(settings.version()).build(), parameters, ToolOutput::text);
        }

        @Override
        public List<ToolDefinition<?, ?>> definitions(Settings settings)
        {
            described++;
            return List.of(definition(settings));
        }

        @Override
        public ToolSet open(Settings settings, ToolSetContext context)
        {
            opened++;
            assembly = context;
            trace.add("open:" + settings.name());
            if (errorOpen) {
                closed++;
                throw new AssertionError("Broken backend initialization");
            }
            if (failOpen) {
                closed++;
                throw new IllegalStateException("private provider diagnostic");
            }
            var contract = definition(mismatch ? new Settings(settings.name(), settings.version() + 1, settings.prefix()) : settings);
            AgentTool<Input, String> tool = new AgentTool<>()
            {
                @Override
                public ToolDefinition<Input, String> definition() { return contract; }

                @Override
                public ToolRequirements requirements(Input input, ToolPreparationContext preparation)
                {
                    assertThat(preparation.invocationId()).contains("call");
                    return requirements;
                }

                @Override
                public ToolExecution<String> execute(Input input, ToolExecutionContext execution)
                {
                    invocation = execution;
                    assertThat(execution.grants()).isSameAs(requirements);
                    if (reference != null) {
                        try {
                            assertThat(Files.readString(checkpoint(reference))).contains("tool-start", "\"started\":true");
                        }
                        catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    }
                    execution.recordPrepared(nullIntent ? null : new ToolResource("sample.resource", "owned-sample"));
                    executed++;
                    entered.countDown();
                    if (throwDuringExecution) {
                        throw new IllegalStateException("private execution diagnostic");
                    }
                    CompletableFuture<ToolOutcome<String>> observed = pending ? completion : new CompletableFuture<>();
                    if (schedule) {
                        assembly.executor().execute(() -> observed.complete(ToolOutcome.completed(settings.prefix() + ":" + input.count())));
                    }
                    else if (!pending) {
                        observed.complete(uncertain ? ToolOutcome.uncertain(new ToolFailure("UNKNOWN", "Effect uncertain"))
                                : failure != null ? ToolOutcome.failed(failure) : ToolOutcome.completed(settings.prefix() + ":" + input.count()));
                    }
                    return new ToolExecution<>()
                    {
                        @Override
                        public CompletionStage<ToolOutcome<String>> completion() { return observed.minimalCompletionStage(); }

                        @Override
                        public void cancel() { cancelled++; }

                        @Override
                        public CompletionStage<ToolStopResult> stopAndVerify()
                        {
                            return CompletableFuture.completedStage(verified && observed.isDone()
                                    ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Work retained"));
                        }

                        @Override
                        public void committed()
                        {
                            committed++;
                            if (reference != null) {
                                try {
                                    assertThat(Files.readString(checkpoint(reference))).contains("tool-result");
                                }
                                catch (Exception error) {
                                    throw new AssertionError(error);
                                }
                            }
                        }
                    };
                }
            };
            return new ToolSet()
            {
                private boolean released;

                @Override
                public List<AgentTool<?, ?>> tools()
                {
                    if (failInspection || released) {
                        throw new IllegalStateException("Tool set unavailable");
                    }
                    return List.of(tool);
                }

                @Override
                public void close()
                {
                    if (refuseClose) {
                        throw new IllegalStateException("Owned resources still active");
                    }
                    if (!released) {
                        trace.add("close:" + settings.name());
                        closed++;
                        released = true;
                    }
                }
            };
        }
    }

    private static final class Client
            implements ModelClient
    {
        final List<ModelRequest> requests = new ArrayList<>();
        String tool = "sample";
        String arguments = "{\"count\":2}";
        boolean respondWithTool = true;
        boolean supported = true;

        @Override
        public ModelCapabilities capabilities(String model) { return new ModelCapabilities(supported, Set.of(), Set.of(), Set.of()); }

        @Override
        public AgentProviderStatus probe(Optional<String> model)
        {
            return new AgentProviderStatus(new AgentLaneId("sample-api"), AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none());
        }

        @Override
        public ModelCall generate(ModelRequest request, ModelEventSink sink)
        {
            requests.add(request);
            boolean tools = respondWithTool && requests.size() == 1 && request.input() instanceof ModelInput.UserMessage;
            var response = new ModelResponse(tools ? "" : "answer", tools ? List.of(new ModelResponse.ToolCall("call", tool, arguments)) : List.of(),
                    tools ? ModelResponse.StopReason.TOOL_CALLS : ModelResponse.StopReason.COMPLETED, new ModelReplayState("sample-api", 1, "replay"), Optional.empty());
            return new ModelCall()
            {
                @Override
                public CompletionStage<ModelResponse> completion() { return CompletableFuture.completedStage(response); }

                @Override
                public void cancel() {}
            };
        }

        @Override
        public void close() {}
    }

    private static final class NoProcesses
            implements ProcessSupervisor
    {
        @Override
        public PreparedProcess prepare(ProcessSpec spec) { throw new AssertionError("No native process"); }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses() { throw new AssertionError("No recovery"); }

        @Override
        public OwnedProcessRecovery recoverOwnedProcess(String id) { throw new AssertionError("No recovery"); }

        @Override
        public boolean launchedHere(String id) { throw new AssertionError("No launch"); }

        @Override
        public void acknowledge(String id) { throw new AssertionError("No acknowledgement"); }
    }

    private ApiEngineConfig.Builder config(Client client)
    {
        return ApiEngineConfig.builder(new ConnectorIdentity("sample-api", "work"), new AgentLaneId("sample-api-work"), "model", () -> client)
                .shutdownTimeout(Duration.ofMillis(100));
    }

    private ConnectorContext host() { return new ConnectorContext(directory.resolve("profile"), new NoProcesses(), ToolServer.DEFAULT); }

    private AgentSessionContext context(SessionAccess access, Optional<AgentSessionRef> reference, List<Tool> legacy, AgentApprovalHandler approvals)
    {
        return new AgentSessionContext("task", directory, directory.resolve("runtime"), directory.resolve("runtime/provider"), AgentPermissionPolicy.AUTO,
                reference, approvals, Optional.empty(), Optional.empty(), access, "", legacy);
    }

    private AgentSessionContext tools() { return context(SessionAccess.TOOLS_ONLY, Optional.empty(), List.of(), allow()); }

    private static AgentApprovalHandler allow() { return (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())); }

    private static AgentTurnCommand command() { return new AgentTurnCommand("turn", "hello", List.of(), Duration.ZERO); }

    private static AgentTurnResult run(AgentSession session)
            throws Exception
    {
        return session.start(command()).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private Path checkpoint(AgentSessionRef reference) { return directory.resolve("profile/api-sessions").resolve(reference.providerValue()).resolve("checkpoint.json"); }

    private static ModelInput.ToolResult result(Client client) { return ((ModelInput.ToolResults) client.requests.getLast().input()).results().getFirst(); }

    @Test
    void bindingAndBuilderSnapshotConfigurationWithoutOpeningResources()
    {
        Provider provider = new Provider();
        ToolProviderBinding binding = ToolProviderBinding.of(provider, SETTINGS);
        assertThat(binding.id()).isEqualTo("sample-provider");
        assertThat(binding.toString()).contains("redacted").doesNotContain("prefix", "count");
        assertThat(binding.definitions()).hasSize(1);
        assertThatThrownBy(() -> binding.definitions().clear()).isInstanceOf(UnsupportedOperationException.class);
        var builder = config(new Client()).toolProvider(binding);
        var first = builder.build();
        builder.toolProvider(provider, new Settings("other", 1, "other"));
        assertThat(first.toolProviders).hasSize(1);
        assertThat(builder.build().toolProviders).hasSize(2);
        assertThat(provider.opened).isZero();
        assertThatThrownBy(() -> ToolProviderBinding.of(null, SETTINGS)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolProviderBinding.of(provider, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> binding.open(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.toolProvider((ToolProviderBinding) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void validationIsPureAndTextSessionsDoNotInstallConfiguredTools()
            throws Exception
    {
        Provider provider = new Provider();
        Client client = new Client();
        client.respondWithTool = false;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host())) {
            driver.validate(tools());
            assertThat(provider.opened).isZero();
            assertThat(directory.resolve("profile")).doesNotExist();
            try (AgentSession session = driver.open(context(SessionAccess.TEXT_ONLY, Optional.empty(), List.of(), allow()), event -> {})) {
                assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(client.requests.getFirst().tools()).isEmpty();
                assertThat(provider.opened).isZero();
            }
        }
    }

    @Test
    void typedToolsShareTheEngineApprovalJournalAndModelTransport()
            throws Exception
    {
        Provider provider = new Provider();
        Client client = new Client();
        AtomicInteger approvals = new AtomicInteger();
        AgentApprovalHandler approval = (request, timeout) -> {
            approvals.incrementAndGet();
            assertThat(provider.bound).isEqualTo(1);
            assertThat(provider.executed).isZero();
            return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
        };
        try (var callbacks = Executors.newSingleThreadExecutor();
                ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).toolExecutor(callbacks).approveTools(false).build(), host());
                AgentSession session = driver.open(context(SessionAccess.TOOLS_ONLY, Optional.empty(), List.of(), approval), event -> {})) {
            provider.reference = session.reference();
            assertThat(provider.assembly.session().access()).isEqualTo(SessionAccess.TOOLS_ONLY);
            assertThat(provider.assembly.connector().profileDirectory()).isEqualTo(directory.resolve("profile"));
            assertThat(provider.assembly.executor()).isSameAs(callbacks);
            assertThat(provider.assembly.shutdownTimeout()).isEqualTo(Duration.ofMillis(100));
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(approvals).hasValue(1);
            assertThat(result(client)).isEqualTo(new ModelInput.ToolResult("call", "value:2", false));
            assertThat(provider.bound).isEqualTo(1);
            assertThat(provider.committed).isEqualTo(1);
            assertThat(provider.invocation.grants().capabilities()).containsExactly(ToolCapability.HOST_INVOKE);
        }
        assertThat(provider.closed).isEqualTo(1);
    }

    @Test
    void duplicateNamesInvalidSchemasAndUnsupportedModelsFailBeforeProfileIo()
    {
        Provider provider = new Provider();
        Client client = new Client();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host())) {
            Tool conflict = Tool.withSchema("sample", "Conflict", "{}", arguments -> "never");
            assertThatThrownBy(() -> driver.open(context(SessionAccess.TOOLS_ONLY, Optional.empty(), List.of(conflict), allow()), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class);
            provider.unsupportedSchema = true;
            assertThatThrownBy(() -> driver.validate(tools())).isInstanceOf(IllegalArgumentException.class);
            provider.unsupportedSchema = false;
            client.supported = false;
            assertThatThrownBy(() -> driver.validate(tools())).isInstanceOf(AgentUnavailableException.class);
            assertThat(provider.opened).isZero();
            assertThat(directory.resolve("profile")).doesNotExist();
        }
    }

    @Test
    void readOnlyCodingModeStillReservesTheWithheldEditToolName()
    {
        Provider provider = new Provider();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(new Client()).codingTools(CodingTools.defaults())
                .toolProvider(provider, new Settings("apply_patch", 1, "value")).build(), host())) {
            assertThatThrownBy(() -> driver.validate(context(SessionAccess.READ_ONLY, Optional.empty(), List.of(), allow())))
                    .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("reserved");
            assertThat(provider.opened).isZero();
            assertThat(directory.resolve("profile")).doesNotExist();
        }
    }

    @Test
    void distinctConfigurationsOfOneFamilyCanCoexistAndCloseInReverseOrder()
            throws Exception
    {
        Provider provider = new Provider();
        Client client = new Client();
        client.respondWithTool = false;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS)
                .toolProvider(provider, new Settings("other", 1, "other")).build(), host());
                AgentSession session = driver.open(tools(), event -> {})) {
            assertThat(provider.opened).isEqualTo(2);
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(client.requests.getFirst().tools()).extracting(ModelRequest.ToolDefinition::name).containsExactly("sample", "other");
        }
        assertThat(provider.trace).containsExactly("open:sample", "open:other", "close:other", "close:sample");
    }

    @Test
    void failedOpenClosesPreviouslyReturnedSetsAndInspectionFailuresAreOwned()
    {
        for (boolean inspection : List.of(false, true)) {
            Provider first = new Provider();
            Provider second = new Provider();
            second.failOpen = !inspection;
            second.failInspection = inspection;
            try (ApiAgentDriver driver = new ApiAgentDriver(config(new Client()).toolProvider(first, SETTINGS)
                    .toolProvider(second, new Settings("other", 1, "other")).build(), host())) {
                assertThatThrownBy(() -> driver.open(tools(), event -> {})).isInstanceOf(AgentUnavailableException.class);
                assertThat(first.closed).isEqualTo(1);
                assertThat(second.closed).isEqualTo(1);
            }
        }
    }

    @Test
    void mismatchedOpenedContractsAreClosedBeforeAUsableSessionEscapes()
    {
        Provider provider = new Provider();
        provider.mismatch = true;
        Client client = new Client();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host())) {
            assertThatThrownBy(() -> driver.open(tools(), event -> {})).isInstanceOf(AgentUnavailableException.class);
            assertThat(provider.closed).isEqualTo(1);
            assertThat(client.requests).isEmpty();
        }
    }

    @Test
    void failedOpenCleanupRetainsTheProfileClaimAndDriverCanRetryClose()
    {
        Provider first = new Provider();
        first.refuseClose = true;
        Provider second = new Provider();
        second.failOpen = true;
        ApiAgentDriver driver = new ApiAgentDriver(config(new Client()).toolProvider(first, SETTINGS)
                .toolProvider(second, new Settings("other", 1, "other")).build(), host());
        try {
            assertThatThrownBy(() -> driver.open(tools(), event -> {})).isInstanceOf(AgentUnavailableException.class);
            assertThat(first.closed).isZero();
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(first.closed).isZero();
        }
        finally {
            first.refuseClose = false;
            driver.close();
        }
        assertThat(first.closed).isEqualTo(1);
    }

    @Test
    void invalidArgumentsAndDenialsCannotExecuteAndMessagesAreSanitized()
            throws Exception
    {
        for (String mode : List.of("schema", "domain", "deny")) {
            Provider provider = new Provider();
            Client client = new Client();
            client.arguments = mode.equals("schema") ? "{\"count\":\"2\"}" : mode.equals("domain") ? "{\"count\":0}" : "{\"count\":2}";
            AtomicInteger approvals = new AtomicInteger();
            AgentApprovalHandler deny = (request, timeout) -> {
                approvals.incrementAndGet();
                return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
            };
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host());
                    AgentSession session = driver.open(context(SessionAccess.TOOLS_ONLY, Optional.empty(), List.of(), deny), event -> {})) {
                assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(provider.executed).isZero();
                assertThat(approvals).hasValue(mode.equals("deny") ? 1 : 0);
                assertThat(result(client).error()).isTrue();
                assertThat(result(client).text()).contains(mode.equals("deny") ? "APPROVAL_DENIED" : "INVALID_ARGUMENTS").doesNotContain("private");
            }
        }
    }

    @Test
    void toolsOnlyReadonlyAndFullSessionsDeriveAuthorityAndUnknownCapabilitiesFailClosed()
            throws Exception
    {
        for (String mode : List.of("read-denied", "write-denied", "read", "write", "unknown", "network")) {
            Provider provider = new Provider();
            Client client = new Client();
            SessionAccess access = mode.equals("read-denied") ? SessionAccess.TOOLS_ONLY : mode.equals("write") ? SessionAccess.FULL : SessionAccess.READ_ONLY;
            provider.requirements = switch (mode) {
                case "read-denied", "read" -> ToolRequirements.builder().read(Path.of("note")).build();
                case "write-denied", "write" -> ToolRequirements.builder().write(Path.of("note")).build();
                case "network" -> ToolRequirements.builder().require(ToolCapability.NETWORK_ACCESS).build();
                default -> ToolRequirements.builder().require(new ToolCapability("custom.unknown")).build();
            };
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host());
                    AgentSession session = driver.open(context(access, Optional.empty(), List.of(), allow()), event -> {})) {
                assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                boolean permitted = mode.equals("read") || mode.equals("write");
                assertThat(provider.executed).isEqualTo(permitted ? 1 : 0);
                assertThat(result(client).error()).isEqualTo(!permitted);
                if (!permitted) {
                    assertThat(result(client).text()).contains("PERMISSION_DENIED");
                }
            }
        }
    }

    @Test
    void typedFailuresSupportTheFullFailureContractAndHostOutputBudgets()
            throws Exception
    {
        Provider provider = new Provider();
        provider.failure = new ToolFailure("HTTP_429", "x".repeat(800));
        Client client = new Client();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).maximumToolOutputChars(1200).toolProvider(provider, SETTINGS).build(), host());
                AgentSession session = driver.open(tools(), event -> {})) {
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(result(client).text()).contains("HTTP_429", "x".repeat(800));
        }
    }

    @Test
    void cancellingAsyncExecutionKeepsTheObservedResultAndResumeDoesNotReplayIt()
            throws Exception
    {
        Provider provider = new Provider();
        provider.pending = true;
        Client client = new Client();
        AgentSessionRef reference;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host());
                AgentSession session = driver.open(tools(), event -> {})) {
            reference = session.reference();
            provider.reference = reference;
            var turn = session.start(command());
            try {
                assertThat(provider.entered.await(1, TimeUnit.SECONDS)).isTrue();
                turn.cancel();
                assertThat(turn.completion().toCompletableFuture()).isNotDone();
            }
            finally {
                provider.completion.complete(ToolOutcome.completed("observed success"));
            }
            assertThat(turn.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        }
        Provider resumed = new Provider();
        Client next = new Client();
        next.respondWithTool = false;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(next).toolProvider(resumed, SETTINGS).build(), host());
                AgentSession session = driver.open(context(SessionAccess.TOOLS_ONLY, Optional.of(reference), List.of(), allow()), event -> {})) {
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(next.requests.getFirst().input()).isEqualTo(new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call", "observed success", false))));
            assertThat(resumed.executed).isZero();
        }
    }

    @Test
    void unverifiedExecutionAndCloseRetainClaimsUntilStopCanBeConfirmed()
            throws Exception
    {
        Provider provider = new Provider();
        provider.uncertain = true;
        provider.verified = false;
        Client client = new Client();
        ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host());
        AgentSession session = driver.open(tools(), event -> {});
        var reference = session.reference();
        try {
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(session.resumable()).isFalse();
            assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(provider.closed).isZero();
            assertThatThrownBy(() -> driver.open(context(SessionAccess.TOOLS_ONLY, Optional.of(reference), List.of(), allow()), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class);
        }
        finally {
            provider.verified = true;
            session.close();
            driver.close();
        }
        Provider next = new Provider();
        try (ApiAgentDriver resumed = new ApiAgentDriver(config(new Client()).toolProvider(next, SETTINGS).build(), host());
                AgentSession recovered = resumed.open(context(SessionAccess.TOOLS_ONLY, Optional.of(reference), List.of(), allow()), event -> {})) {
            assertThat(recovered.resumable()).isFalse();
            assertThatThrownBy(() -> recovered.start(command())).isInstanceOf(AgentUnavailableException.class);
        }
    }

    @Test
    void toolSetCloseIsRetryableAndKeepsTheSessionWriter()
            throws Exception
    {
        Provider provider = new Provider();
        Client client = new Client();
        client.respondWithTool = false;
        ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host());
        AgentSession session = driver.open(tools(), event -> {});
        provider.refuseClose = true;
        try {
            assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> driver.open(context(SessionAccess.TOOLS_ONLY, Optional.of(session.reference()), List.of(), allow()), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class);
        }
        finally {
            provider.refuseClose = false;
            session.close();
            session.close();
            driver.close();
        }
        assertThat(provider.closed).isEqualTo(1);
    }

    @Test
    void pendingTypedBatchesRejectContractChangesAndMissingManifest()
            throws Exception
    {
        Provider provider = new Provider();
        Client client = new Client();
        AgentSessionRef reference;
        CountDownLatch waiting = new CountDownLatch(1);
        AgentApprovalHandler interrupt = (request, timeout) -> {
            waiting.countDown();
            return new CompletableFuture<>();
        };
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolProvider(provider, SETTINGS).build(), host());
                AgentSession session = driver.open(context(SessionAccess.TOOLS_ONLY, Optional.empty(), List.of(), interrupt), event -> {})) {
            reference = session.reference();
            var turn = session.start(command());
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            turn.cancel();
            assertThat(turn.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        }
        for (boolean missing : List.of(false, true)) {
            if (missing) {
                try (ApiSessionStore store = ApiSessionStore.open(directory.resolve("profile"), reference.providerValue(), true, config(new Client()).build())) {
                    ApiCheckpoint state = store.state();
                    ApiRequestSetup setup = state.setup().orElseThrow();
                    var legacy = new ApiRequestSetup(setup.model(), setup.instructions(), setup.effort(), setup.tier(), setup.tools(), Optional.empty());
                    store.save(new ApiCheckpoint(state.version(), state.id(), state.identity(), state.lane(), state.phase(), state.journal(), state.receipts(),
                            state.replay(), Optional.of(legacy), state.input()));
                }
            }
            Provider changed = new Provider();
            try (ApiAgentDriver driver = new ApiAgentDriver(config(new Client()).toolProvider(changed,
                    new Settings("sample", missing ? 1 : 2, "value")).build(), host());
                    AgentSession session = driver.open(context(SessionAccess.TOOLS_ONLY, Optional.of(reference), List.of(), allow()), event -> {})) {
                assertThatThrownBy(() -> session.start(command())).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("original tool definitions");
            }
        }
    }

    @Test
    void defaultProviderExecutorDoesNotDeadlockASingleTurnWorker()
            throws Exception
    {
        Provider provider = new Provider();
        provider.schedule = true;
        Client client = new Client();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).maximumSessions(1).toolProvider(provider, SETTINGS).build(), host());
                AgentSession session = driver.open(tools(), event -> {})) {
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(result(client).text()).isEqualTo("value:2");
        }
    }

    @Test
    void backendInitializationErrorsAlsoCleanPreviouslyReturnedSets()
    {
        Provider first = new Provider();
        Provider second = new Provider();
        second.errorOpen = true;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(new Client()).toolProvider(first, SETTINGS)
                .toolProvider(second, new Settings("other", 1, "other")).build(), host())) {
            assertThatThrownBy(() -> driver.open(tools(), event -> {})).isInstanceOf(AssertionError.class);
            assertThat(first.closed).isEqualTo(1);
            assertThat(second.closed).isEqualTo(1);
        }
    }

    @Test
    void malformedResourceIntentFailsBeforeTheBackendEffect()
            throws Exception
    {
        Provider provider = new Provider();
        provider.nullIntent = true;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(new Client()).toolProvider(provider, SETTINGS).build(), host());
                AgentSession session = driver.open(tools(), event -> {})) {
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(provider.executed).isZero();
            assertThat(session.resumable()).isFalse();
        }
    }

    @Test
    void partialExecutionFailureStillRequiresToolSetShutdownProof()
            throws Exception
    {
        Provider provider = new Provider();
        provider.throwDuringExecution = true;
        provider.refuseClose = true;
        ApiAgentDriver driver = new ApiAgentDriver(config(new Client()).toolProvider(provider, SETTINGS).build(), host());
        AgentSession session = driver.open(tools(), event -> {});
        try {
            assertThat(run(session).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(session.resumable()).isFalse();
            assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(provider.closed).isZero();
        }
        finally {
            provider.refuseClose = false;
            session.close();
            driver.close();
        }
    }
}

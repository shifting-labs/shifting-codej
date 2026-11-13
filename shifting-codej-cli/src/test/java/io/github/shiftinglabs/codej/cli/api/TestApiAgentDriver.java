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

import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnFailureKind;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.driver.ProviderTurnRef;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiAgentDriver
{
    @TempDir
    Path directory;

    @Test
    void factoryAndMetadataStartNoInferenceOrProcesses()
    {
        FakeClient client = new FakeClient();
        ApiEngineConfig config = config(client).build();
        ApiConnector factory = new ApiConnector("fake-api");
        assertThat(factory.id()).isEqualTo("fake-api");
        assertThat(factory.configurationType()).isEqualTo(ApiEngineConfig.class);
        NoProcesses supervisor = new NoProcesses();
        try (ApiAgentDriver driver = factory.create(config, new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT))) {
            assertThat(driver.id()).isEqualTo(new AgentLaneId("fake-api-work"));
            assertThat(driver.identity()).isEqualTo(new ConnectorIdentity("fake-api", "work"));
            assertThat(driver.label()).isEqualTo("fake-api");
            assertThat(driver.capabilities().attachmentMediaTypes()).contains("image/png");
            assertThat(driver.capabilities().steering()).isFalse();
            assertThat(driver.reviewChildAgents()).isFalse();
            assertThat(driver.probe().laneId()).isEqualTo(driver.id());
            assertThat(driver.probe(Optional.of("other")).available()).isTrue();
            assertThat(client.probed).contains("other");
            assertThat(driver.models()).extracting(ProviderModel::id).containsExactly("model");
            assertThat(driver.quota().message()).contains("Unavailable");
            driver.validate(context(Optional.empty(), List.of(), allow()));
            assertThat(client.requests).isEmpty();
            assertThat(directory.resolve("profile")).doesNotExist();
        }
        assertThat(client.closed).isTrue();
        assertThat(supervisor.closed).isFalse();
        assertThatThrownBy(() -> factory.create(null, connectorContext())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.create(config, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiConnector("wrong").create(config, connectorContext())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedPermissionsAndReferencesBeforeProfileIo()
    {
        try (ApiAgentDriver driver = driver(new FakeClient())) {
            AgentSessionContext text = context(Optional.empty(), List.of(), allow());
            for (SessionAccess access : List.of(SessionAccess.FULL, SessionAccess.READ_ONLY, SessionAccess.REVIEW_READ_ONLY)) {
                AgentSessionContext unsupported = text.withTaskScope(new AgentPermissionScope(AgentPermissionPolicy.AUTO, access, false), false);
                assertThatThrownBy(() -> driver.open(unsupported, event -> {})).isInstanceOf(AgentUnavailableException.class);
            }
            assertThatThrownBy(() -> driver.validate(text.withTaskScope(text.scope().withWebSearch(true), false)))
                    .isInstanceOf(AgentUnavailableException.class);
            for (AgentSessionRef reference : List.of(new AgentSessionRef(driver.id(), 1, "../../escape"),
                    new AgentSessionRef(new AgentLaneId("another"), 1, java.util.UUID.randomUUID().toString()),
                    new AgentSessionRef(driver.id(), 2, java.util.UUID.randomUUID().toString()))) {
                assertThatThrownBy(() -> driver.open(context(Optional.of(reference), List.of(), allow()), event -> {}))
                        .isInstanceOf(AgentUnavailableException.class);
            }
            assertThatThrownBy(() -> driver.validate(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> driver.open(text, null)).isInstanceOf(NullPointerException.class);
            assertThat(directory.resolve("profile")).doesNotExist();
        }
    }

    @Test
    void runsTextAndPreservesExistingSessionAndTurnDefaults()
            throws Exception
    {
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), events::add)) {
            assertThat(session.resumable()).isTrue();
            assertThat(session.retained()).isFalse();
            session.allowReuse();
            session.preventReuse();
            assertThat(session.prepareTurn(context(Optional.empty(), List.of(), allow()), events::add)).isFalse();
            RunningTurn turn = session.start(command("Hello"));
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(turn).finalResponse()).contains("answer");
            assertThat(turn.reference()).isEqualTo(new ProviderTurnRef("turn"));
            assertThat(turn.setup()).isEmpty();
            turn.cancel();
            assertThat(events.stream().filter(event -> event instanceof ProviderAgentEvent.Message).map(event -> (ProviderAgentEvent.Message) event)
                    .filter(event -> event.phase() == ProviderAgentEvent.MessagePhase.PARTIAL).map(ProviderAgentEvent.Message::text)).containsExactly("ans", "wer");
            assertThat(client.requests).hasSize(1);
            assertThat(client.requests.getFirst().input()).isEqualTo(new ModelInput.UserMessage("Hello", List.of()));
        }
    }

    @Test
    void perTurnModelOptionsAndDeadlineReachEveryModelRequest()
            throws Exception
    {
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).maximumOutputTokens(128).build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            AgentTurnCommand command = new AgentTurnCommand("options", "Hello", List.of(), Duration.ofSeconds(1),
                    Optional.of("other"), Optional.of("high"), Optional.of("standard"));
            assertThat(done(session.start(command)).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            ModelRequest request = client.requests.getFirst();
            assertThat(request.model()).isEqualTo("other");
            assertThat(request.effort()).contains("high");
            assertThat(request.serviceTier()).contains("standard");
            assertThat(request.maximumOutputTokens()).isEqualTo(128);
            assertThat(request.deadline()).isPresent();
            assertThat(request.instructions()).contains("Host instructions");
            assertThatThrownBy(() -> session.start(new AgentTurnCommand("invalid", "Hello", List.of(), Duration.ZERO,
                    Optional.of("model"), Optional.of("unknown"), Optional.empty()))).isInstanceOf(AgentUnavailableException.class);
            assertThat(client.requests).hasSize(1);
        }
    }

    @Test
    void hostToolsAreApprovedExecutedAndReturnedWithReplayAndUsage()
            throws Exception
    {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        Tool tool = Tool.of("lookup", "Reads an issue", Issue.class, issue -> {
            executions.incrementAndGet();
            return "Open: " + issue.key();
        });
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> completed(toolResponse("lookup", "{\"key\":\"ENG-1\"}")));
        client.script.add((request, sink) -> {
            assertThat(request.replayState().orElseThrow().payload()).isEqualTo("after-call");
            assertThat(((ModelInput.ToolResults) request.input()).results()).containsExactly(new ModelInput.ToolResult("call-1", "Open: ENG-1", false));
            assertThat(request.tools()).extracting(ModelRequest.ToolDefinition::name).containsExactly("lookup");
            return answer(request, sink);
        });
        AgentApprovalHandler approval = (request, wait) -> {
            approvals.incrementAndGet();
            assertThat(request.kind()).isEqualTo(ProviderApprovalRequest.Kind.TOOL);
            assertThat(request.availableDecisions()).containsExactly(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY);
            assertThat(request.sandboxed()).isFalse();
            return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
        };
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(tool), approval), events::add)) {
            assertThat(done(session.start(command("Read ENG-1"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(executions).hasValue(1);
            assertThat(approvals).hasValue(1);
            assertThat(events).anyMatch(event -> event instanceof ProviderAgentEvent.Tool activity
                    && activity.output().map(ProviderAgentEvent.ToolOutput::text).orElse("").equals("Open: ENG-1"));
            assertThat(events.stream().filter(event -> event instanceof ProviderAgentEvent.Usage).map(event -> (ProviderAgentEvent.Usage) event))
                    .hasSize(2).allMatch(event -> event.sample().semantics() == ProviderUsageSample.Semantics.DELTA && event.sample().cost().isEmpty());
            assertThat(Files.readString(checkpoint(session.reference()))).contains("tool-start", "tool-result", "Open: ENG-1");
        }
    }

    @Test
    void invalidArgumentsUnknownToolsAndDenialsReturnErrorsWithoutExecuting()
            throws Exception
    {
        for (String mode : List.of("invalid", "unknown", "denied")) {
            AtomicInteger executions = new AtomicInteger();
            Tool tool = Tool.of("lookup", "Lookup", Issue.class, issue -> {
                executions.incrementAndGet();
                return "Never";
            });
            FakeClient client = new FakeClient();
            client.script.add((request, sink) -> completed(toolResponse(mode.equals("unknown") ? "absent" : "lookup",
                    mode.equals("invalid") ? "{\"key\":42}" : "{\"key\":\"ENG-1\"}")));
            client.script.add((request, sink) -> {
                assertThat(((ModelInput.ToolResults) request.input()).results().getFirst().error()).isTrue();
                return answer(request, sink);
            });
            AgentApprovalHandler handler = mode.equals("denied")
                    ? (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()) : allow();
            try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(tool), handler), event -> {})) {
                assertThat(done(session.start(command("Lookup"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(executions).hasValue(0);
            }
        }
    }

    @Test
    void schemaAssertionsAreEnforcedOrRejectedBeforeOpening()
    {
        ApiToolRegistry registry = new ApiToolRegistry(List.of(Tool.withSchema("nested", "Nested", """
                {"type":"object","required":["items"],"additionalProperties":false,"properties":{
                  "items":{"type":"array","items":{"type":"object","required":["value"],"properties":{
                    "value":{"type":"string","enum":["yes","no"]}}}}}}
                """, text -> "done")));
        assertThat(registry.tool("nested", "{\"items\":[{\"value\":\"yes\"}]}" )).isNotNull();
        for (String invalid : List.of("{}", "{\"items\":[{\"value\":\"other\"}]}", "{\"items\":42}", "{\"items\":[],\"extra\":1}", "{} {}")) {
            assertThatThrownBy(() -> registry.tool("nested", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        try (ApiAgentDriver driver = driver(new FakeClient())) {
            Tool unsupported = Tool.withSchema("unsupported", "Unsupported", "{\"type\":\"object\",\"oneOf\":[]}", text -> "Never");
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(unsupported), allow()), event -> {})).isInstanceOf(IllegalArgumentException.class);
            assertThat(directory.resolve("profile")).doesNotExist();
        }
    }

    @Test
    void replaySurvivesDriverRestartAndKeysAreAbsentFromVisibleEvents()
            throws Exception
    {
        String secret = "key-very-secret";
        FakeClient first = new FakeClient();
        first.script.add((request, sink) -> {
            sink.textDelta("key-very-");
            sink.textDelta("secret");
            return completed(response(secret, "opaque-private-state", List.of(), ModelResponse.StopReason.COMPLETED));
        });
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AgentSessionRef reference;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(first).redactor(SecretRedactor.fromEnvironment(Map.of("API_KEY", secret))).build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), events::add)) {
            assertThat(done(session.start(command("Hello"))).finalResponse()).contains("[REDACTED]");
            reference = session.reference();
        }
        assertThat(events.toString()).doesNotContain(secret, "opaque-private-state");
        FakeClient second = new FakeClient();
        try (ApiAgentDriver driver = driver(second); AgentSession session = driver.open(context(Optional.of(reference), List.of(), allow()), event -> {})) {
            assertThat(session.reference()).isEqualTo(reference);
            assertThat(done(session.start(command("Continue"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(second.requests.getFirst().replayState().orElseThrow().payload()).isEqualTo("opaque-private-state");
        }
    }

    @Test
    void attachmentsAreDurablyCopiedBeforeInference()
            throws Exception
    {
        Path original = Files.writeString(directory.resolve("image"), "attachment-content");
        AgentAttachment attachment = new AgentAttachment("image", "image.png", "image/png", original);
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(new AgentTurnCommand("attachment", "Look", List.of(attachment), Duration.ZERO))).state())
                    .isEqualTo(AgentTurnResult.State.COMPLETED);
            Path staged = ((ModelInput.UserMessage) client.requests.getFirst().input()).attachments().getFirst().path();
            assertThat(staged).isNotEqualTo(original).hasContent("attachment-content");
            Files.delete(original);
            assertThat(staged).hasContent("attachment-content");
        }
    }

    @Test
    void normalizedAuthenticationAndTransientFailuresKeepLegacyResultFields()
            throws Exception
    {
        for (ModelClientException.Kind kind : List.of(ModelClientException.Kind.AUTHENTICATION_REQUIRED, ModelClientException.Kind.RATE_LIMITED)) {
            FakeClient client = new FakeClient();
            client.script.add((request, sink) -> failed(new ModelClientException(kind, "Safe failure", false)));
            try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
                AgentTurnResult result = done(session.start(command("Hello")));
                assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
                assertThat(result.transientFailure()).isEqualTo(kind == ModelClientException.Kind.RATE_LIMITED);
                assertThat(result.failureKind()).isEqualTo(kind == ModelClientException.Kind.AUTHENTICATION_REQUIRED
                        ? Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED) : Optional.empty());
                assertThat(session.resumable()).isTrue();
                assertThat(client.requests).hasSize(1);
            }
        }
    }

    @Test
    void uncertainRequestsCannotBeSilentlyResubmitted()
            throws Exception
    {
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> failed(new ModelClientException(ModelClientException.Kind.PROTOCOL, "Stream truncated", true)));
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(session.resumable()).isFalse();
            assertThatThrownBy(() -> session.start(command("Retry"))).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("reconciliation");
            assertThat(client.requests).hasSize(1);
        }
    }

    @Test
    void deadlineCancelsAPendingModelCallWithoutRetrying()
            throws Exception
    {
        FakeClient client = new FakeClient();
        PendingCall pending = new PendingCall(true);
        client.script.add((request, sink) -> pending);
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            AgentTurnResult result = done(session.start(new AgentTurnCommand("deadline", "Wait", List.of(), Duration.ofMillis(30))));
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(result.terminalReason()).contains("API turn deadline exceeded");
            assertThat(pending.cancelled).isTrue();
            assertThat(client.requests).hasSize(1);
        }
    }

    @Test
    void onlyOneTurnAndOneWriterCanOwnAConversation()
            throws Exception
    {
        FakeClient client = new FakeClient();
        PendingCall pending = new PendingCall(true);
        CountDownLatch submitted = new CountDownLatch(1);
        client.script.add((request, sink) -> { submitted.countDown(); return pending; });
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
                ApiAgentDriver other = driver(new FakeClient())) {
            RunningTurn turn = session.start(command("Wait"));
            assertThat(submitted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> session.start(command("Second"))).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> other.open(context(Optional.of(session.reference()), List.of(), allow()), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class);
            turn.cancel();
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        }
    }

    @Test
    void closeDoesNotPretendAnUncooperativeModelCallStopped()
            throws Exception
    {
        FakeClient client = new FakeClient();
        PendingCall pending = new PendingCall(false);
        CountDownLatch submitted = new CountDownLatch(1);
        client.script.add((request, sink) -> { submitted.countDown(); return pending; });
        ApiAgentDriver driver = new ApiAgentDriver(config(client).shutdownTimeout(Duration.ofMillis(20)).build(), connectorContext());
        AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
        RunningTurn turn = session.start(command("Wait"));
        try {
            assertThat(submitted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("could not confirm");
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            try (ApiAgentDriver other = driver(new FakeClient())) {
                assertThatThrownBy(() -> other.open(context(Optional.of(session.reference()), List.of(), allow()), event -> {}))
                        .isInstanceOf(AgentUnavailableException.class);
            }
        }
        finally {
            pending.future.complete(response("late", "late-state", List.of(), ModelResponse.StopReason.COMPLETED));
            done(turn);
            session.close();
            driver.close();
        }
    }

    @Test
    void cancelledToolKeepsItsObservedSuccessAndRecoveryDoesNotExecuteItAgain()
            throws Exception
    {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tool tool = Tool.withSchema("write_report", "Writes a report", "{}", arguments -> {
            executions.incrementAndGet();
            entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    waiting = !release.await(1, TimeUnit.SECONDS);
                }
                catch (InterruptedException failure) {
                    // Deliberately model a host operation whose outcome must be observed before releasing its writer.
                }
            }
            return "Report saved";
        });
        FakeClient first = new FakeClient();
        first.script.add((request, sink) -> completed(toolResponse("write_report", "{}")));
        AgentSessionRef reference;
        try (ApiAgentDriver driver = driver(first); AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
            reference = session.reference();
            RunningTurn turn = session.start(command("Write"));
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                turn.cancel();
                assertThat(turn.completion().toCompletableFuture()).isNotDone();
            }
            finally {
                release.countDown();
            }
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        }
        FakeClient second = new FakeClient();
        second.script.add((request, sink) -> {
            assertThat(((ModelInput.ToolResults) request.input()).results()).containsExactly(new ModelInput.ToolResult("call-1", "Report saved", false));
            return answer(request, sink);
        });
        try (ApiAgentDriver driver = driver(second); AgentSession session = driver.open(context(Optional.of(reference), List.of(tool), allow()), event -> {})) {
            assertThat(done(session.start(command("Continue"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(second.requests).hasSize(2);
            assertThat(second.requests.get(1).input()).isEqualTo(new ModelInput.UserMessage("Continue", List.of()));
            assertThat(executions).hasValue(1);
        }
    }

    @Test
    void unknownToolOutcomeRequiresReconciliationAfterRestart()
            throws Exception
    {
        AgentSessionRef reference;
        try (ApiAgentDriver driver = driver(new FakeClient()); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            done(session.start(command("Hello")));
            reference = session.reference();
        }
        FakeClient client = new FakeClient();
        try (ApiSessionStore store = ApiSessionStore.open(directory.resolve("profile"), reference.providerValue(), true, config(client).build())) {
            var state = store.state().put("phase", "tools");
            state.withArray("receipts").addObject().put("id", "call-1").put("name", "write_report").put("arguments", "{}").put("started", true);
            store.save(state);
        }
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.of(reference), List.of(), allow()), event -> {})) {
            assertThat(session.resumable()).isFalse();
            assertThatThrownBy(() -> session.start(command("Continue"))).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("reconciliation");
            assertThat(client.requests).isEmpty();
        }
    }

    @Test
    void requestBudgetsStopTheLoopAndToolOutputsAreBounded()
            throws Exception
    {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = Tool.withSchema("large", "Large result", "{}", arguments -> { executions.incrementAndGet(); return "x".repeat(100); });
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> completed(toolResponse("large", "{}")));
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).maximumRequests(1).maximumToolOutputChars(12).approveTools(false).build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
            AgentTurnResult result = done(session.start(command("Run")));
            assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
            assertThat(result.terminalReason().orElseThrow()).contains("budget");
            assertThat(client.requests).hasSize(1);
            assertThat(executions).hasValue(1);
            var state = ApiSessionStore.JSON.readTree(Files.readString(checkpoint(session.reference())));
            assertThat(state.path("receipts").get(0).path("result").path("text").asText()).hasSize(12);
        }
    }

    @Test
    void borrowedExecutorRemainsOpenAndCallbackCloseFailsPromptly()
            throws Exception
    {
        try (var executor = Executors.newSingleThreadExecutor()) {
            FakeClient client = new FakeClient();
            ApiAgentDriver driver = new ApiAgentDriver(config(client).executor(executor).build(), connectorContext());
            try (driver; AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {
                if (event instanceof ProviderAgentEvent.Message message && message.phase() == ProviderAgentEvent.MessagePhase.PARTIAL) {
                    assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("callback");
                }
            })) {
                assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            }
            assertThat(executor.isShutdown()).isFalse();
            assertThat(executor.submit(() -> "host alive").get(1, TimeUnit.SECONDS)).isEqualTo("host alive");
        }
    }

    @Test
    void repeatedToolIdsInDifferentResponsesHaveDistinctEventIdentities()
            throws Exception
    {
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", arguments -> "done");
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> completed(toolResponse("lookup", "{}")));
        client.script.add((request, sink) -> completed(toolResponse("lookup", "{}")));
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), events::add)) {
            assertThat(done(session.start(command("Lookup twice"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(events.stream().filter(event -> event instanceof ProviderAgentEvent.Tool toolEvent
                            && toolEvent.state() == ProviderAgentEvent.ActivityState.COMPLETED)
                    .map(event -> event.source().providerItemId().orElseThrow()).distinct()).hasSize(2);
        }
    }

    @Test
    void observedTokenAndToolBudgetsPreventFurtherExecution()
            throws Exception
    {
        for (boolean tokenBudget : List.of(true, false)) {
            AtomicInteger executions = new AtomicInteger();
            Tool tool = Tool.withSchema("lookup", "Lookup", "{}", arguments -> {
                executions.incrementAndGet();
                return "done";
            });
            FakeClient client = new FakeClient();
            client.script.add((request, sink) -> completed(response("", "after-call",
                    List.of(new ModelResponse.ToolCall("call-1", "lookup", "{}"), new ModelResponse.ToolCall("call-2", "lookup", "{}")),
                    ModelResponse.StopReason.TOOL_CALLS)));
            ApiEngineConfig.Builder builder = config(client).maximumToolCalls(1).approveTools(false);
            if (tokenBudget) {
                builder.maximumTokens(5);
            }
            try (ApiAgentDriver driver = new ApiAgentDriver(builder.build(), connectorContext());
                    AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
                assertThat(done(session.start(command("Lookup"))).state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
                assertThat(executions).hasValue(tokenBudget ? 0 : 1);
                assertThat(client.requests).hasSize(1);
            }
        }
    }

    @Test
    void checkpointFailureBeforeToolAdmissionNeverRunsTheHandler()
            throws Exception
    {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", arguments -> {
            executions.incrementAndGet();
            return "never";
        });
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> completed(toolResponse("lookup", "{\"large\":\"" + "x".repeat(2500) + "\"}")));
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).maximumCheckpointBytes(2048).build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
            assertThat(done(session.start(command("Lookup"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(executions).hasValue(0);
            assertThat(session.resumable()).isFalse();
            assertThatThrownBy(() -> session.start(command("Retry"))).isInstanceOf(AgentUnavailableException.class);
        }
    }

    @Test
    void failedEventConsumerCannotAdmitAToolHandler()
            throws Exception
    {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", arguments -> {
            executions.incrementAndGet();
            return "never";
        });
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> completed(toolResponse("lookup", "{}")));
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {
            if (event instanceof ProviderAgentEvent.Tool activity && activity.state() == ProviderAgentEvent.ActivityState.STARTED) {
                throw new IllegalStateException("Event consumer failed");
            }
        })) {
            assertThat(done(session.start(command("Lookup"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(executions).hasValue(0);
            assertThat(session.resumable()).isTrue();
            var state = ApiSessionStore.JSON.readTree(Files.readString(checkpoint(session.reference())));
            assertThat(state.path("receipts").get(0).path("started").asBoolean()).isFalse();
        }
    }

    @Test
    void nonSuccessStopReasonsKeepTheirObservedReplayWithoutReportingSuccess()
            throws Exception
    {
        for (ModelResponse.StopReason reason : List.of(ModelResponse.StopReason.OUTPUT_LIMIT, ModelResponse.StopReason.REFUSED, ModelResponse.StopReason.PAUSED)) {
            FakeClient client = new FakeClient();
            client.script.add((request, sink) -> completed(response("", "observed-terminal", List.of(), reason)));
            try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
                AgentTurnResult result = done(session.start(command("Hello")));
                assertThat(result.state()).isEqualTo(reason == ModelResponse.StopReason.REFUSED ? AgentTurnResult.State.FAILED : AgentTurnResult.State.INTERRUPTED);
                assertThat(result.transientFailure()).isFalse();
                assertThat(session.resumable()).isTrue();
                assertThat(ApiSessionStore.JSON.readTree(Files.readString(checkpoint(session.reference()))).path("replay").path("payload").asText())
                        .isEqualTo("observed-terminal");
            }
        }
    }

    private ApiEngineConfig.Builder config(FakeClient client)
    {
        return ApiEngineConfig.builder(new ConnectorIdentity("fake-api", "work"), new AgentLaneId("fake-api-work"), "model", () -> client).maximumSessions(2);
    }

    private ApiAgentDriver driver(FakeClient client) { return new ApiAgentDriver(config(client).build(), connectorContext()); }

    private ConnectorContext connectorContext() { return new ConnectorContext(directory.resolve("profile"), new NoProcesses(), ToolServer.DEFAULT); }

    private AgentSessionContext context(Optional<AgentSessionRef> reference, List<Tool> tools, AgentApprovalHandler approvals)
    {
        return new AgentSessionContext("task", directory, directory.resolve("runtime"), directory.resolve("runtime/provider"), AgentPermissionPolicy.AUTO,
                reference, approvals, Optional.empty(), Optional.empty(), tools.isEmpty() ? SessionAccess.TEXT_ONLY : SessionAccess.TOOLS_ONLY, "Host instructions", tools);
    }

    private Path checkpoint(AgentSessionRef reference) { return directory.resolve("profile/api-sessions").resolve(reference.providerValue()).resolve("checkpoint.json"); }

    private static AgentApprovalHandler allow() { return (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())); }

    private static AgentTurnCommand command(String text) { return new AgentTurnCommand("turn", text, List.of(), Duration.ZERO); }

    private static AgentTurnResult done(RunningTurn turn)
            throws Exception
    {
        return turn.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static ModelResponse toolResponse(String name, String arguments)
    {
        return response("", "after-call", List.of(new ModelResponse.ToolCall("call-1", name, arguments)), ModelResponse.StopReason.TOOL_CALLS);
    }

    private static ModelResponse response(String text, String replay, List<ModelResponse.ToolCall> tools, ModelResponse.StopReason reason)
    {
        return new ModelResponse(text, tools, reason, new ModelReplayState("fake-api", 1, replay),
                Optional.of(new ProviderUsageSample(Optional.empty(), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, 2, 0, 3, Optional.empty())));
    }

    private static ModelCall answer(ModelRequest request, ModelEventSink sink)
    {
        sink.textDelta("ans");
        sink.textDelta("wer");
        return completed(response("answer", "after-" + request.requestId(), List.of(), ModelResponse.StopReason.COMPLETED));
    }

    private static ModelCall completed(ModelResponse response)
    {
        PendingCall call = new PendingCall(true);
        call.future.complete(response);
        return call;
    }

    private static ModelCall failed(ModelClientException failure)
    {
        PendingCall call = new PendingCall(true);
        call.future.completeExceptionally(failure);
        return call;
    }

    private record Issue(String key) {}

    private static final class PendingCall
            implements ModelCall
    {
        final CompletableFuture<ModelResponse> future = new CompletableFuture<>();
        private final boolean cooperates;
        volatile boolean cancelled;

        PendingCall(boolean cooperates) { this.cooperates = cooperates; }

        @Override
        public CompletionStage<ModelResponse> completion() { return future.minimalCompletionStage(); }

        @Override
        public void cancel()
        {
            cancelled = true;
            if (cooperates) {
                future.cancel(false);
            }
        }
    }

    private static final class FakeClient
            implements ModelClient
    {
        final Queue<BiFunction<ModelRequest, ModelEventSink, ModelCall>> script = new ConcurrentLinkedQueue<>();
        final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
        final List<ModelCall> calls = new CopyOnWriteArrayList<>();
        Optional<String> probed = Optional.empty();
        boolean closed;

        @Override
        public ModelCapabilities capabilities(String model) { return new ModelCapabilities(true, Set.of("image/png"), Set.of("low", "high"), Set.of("standard")); }

        @Override
        public AgentProviderStatus probe(Optional<String> model)
        {
            probed = model;
            return new AgentProviderStatus(new AgentLaneId("fake-api"), AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none());
        }

        @Override
        public List<ProviderModel> models() { return List.of(new ProviderModel("model", "Model", "", List.of(), Optional.empty(), true, List.of("text"), List.of())); }

        @Override
        public ProviderQuotaSnapshot quota() { return ProviderQuotaSnapshot.unavailable("Unavailable"); }

        @Override
        public ModelCall generate(ModelRequest request, ModelEventSink sink)
        {
            requests.add(request);
            var action = script.poll();
            ModelCall call = action == null ? answer(request, sink) : action.apply(request, sink);
            calls.add(call);
            return call;
        }

        @Override
        public void close()
        {
            closed = true;
            calls.forEach(ModelCall::cancel);
        }
    }

    private static final class NoProcesses
            implements ProcessSupervisor, AutoCloseable
    {
        private boolean closed;

        @Override
        public PreparedProcess prepare(ProcessSpec spec) { throw new AssertionError("No native process"); }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses() { throw new AssertionError("No process recovery"); }

        @Override
        public OwnedProcessRecovery recoverOwnedProcess(String launchId) { throw new AssertionError("No process recovery"); }

        @Override
        public boolean launchedHere(String launchId) { throw new AssertionError("No process query"); }

        @Override
        public void acknowledge(String launchId) { throw new AssertionError("No process acknowledgement"); }

        @Override
        public void close() { closed = true; }
    }
}

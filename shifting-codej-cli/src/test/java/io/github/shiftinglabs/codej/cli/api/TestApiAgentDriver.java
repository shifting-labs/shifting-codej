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
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.api.tools.FakeCodingSupervisor;
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
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactor;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPlan;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtension;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionContext;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionProvider;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineObservation;
import io.github.shiftinglabs.codej.cli.spi.extension.ToolPolicy;
import io.github.shiftinglabs.codej.cli.spi.extension.ToolPolicyContext;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompaction;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContext;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContribution;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContributor;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProvider;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProviderBinding;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptSection;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptSessionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiAgentDriver
{
    @TempDir
    Path directory;

    @Test
    void engineSummarySharesBudgetsAndKeepsPrivateInferenceOutsideConversationAndAnswerEvents()
            throws Exception
    {
        FakeClient client = summaryClient();
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        List<EngineObservation> observations = new CopyOnWriteArrayList<>();
        EngineExtensionProvider<String> observer = new EngineExtensionProvider<>()
        {
            @Override
            public String id() { return "summary-observer"; }

            @Override
            public EngineExtension open(String configuration, EngineExtensionContext context)
            {
                return new EngineExtension()
                {
                    @Override
                    public void observe(EngineObservation observation) { observations.add(observation); }
                };
            }
        };
        AtomicReference<AgentSessionRef> clientReference = new AtomicReference<>();
        client.script.add((request, sink) -> {
            assertThat(request.maximumOutputTokens()).isEqualTo(6);
            assertThat(request.tools()).isEmpty();
            assertThat(request.replayState()).isEmpty();
            assertThat(request.history().orElseThrow().exchanges()).isEmpty();
            assertThat(request.instructions()).contains("untrusted context data").doesNotContain("Host instructions");
            assertThat(((ModelInput.UserMessage) request.input()).text()).contains("original input", "old answer").doesNotContain("fresh input");
            sink.textDelta("private summary");
            return completed(response("private summary", "isolated replay", List.of(), ModelResponse.StopReason.COMPLETED));
        });
        client.script.add((request, sink) -> {
            assertThat(request.maximumOutputTokens()).isEqualTo(15);
            assertThat(request.replayState().orElseThrow().payload()).isEqualTo("compact");
            assertThat(request.instructions()).contains("Host instructions").doesNotContain("Summarize the supplied");
            assertThat(request.history().orElseThrow().exchanges()).hasSize(1);
            assertThat(readCheckpoint(clientReference.get()).history().exchanges()).hasSize(1);
            return answer(request, sink);
        });
        var settings = config(client).maximumRequests(2).maximumTokens(20).maximumOutputTokens(128)
                .contextSummarization(context -> Optional.of(new ContextSummarizationPlan(0, 6))).extension(observer, "value").build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), events::add)) {
            clientReference.set(session.reference());
            assertThat(done(session.start(command("original input"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("fresh input"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(readCheckpoint(session.reference()).history().exchanges()).hasSize(2).noneMatch(exchange -> exchange.text().contains("private summary"));
            assertThat(client.compactions).singleElement().satisfies(request -> assertThat(request.compaction().summary()).isEqualTo("private summary"));
        }
        assertThat(events.stream().filter(ProviderAgentEvent.Message.class::isInstance).map(Object::toString).toList())
                .noneMatch(event -> event.contains("private summary"));
        assertThat(events.stream().filter(ProviderAgentEvent.Usage.class::isInstance)).hasSize(3);
        assertThat(observations.stream().filter(EngineObservation.ContextSummaryCompleted.class::isInstance)).hasSize(1);
        assertThat(observations.stream().filter(EngineObservation.ModelCompleted.class::isInstance)).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "unsupported", "boundary", "input", "null-policy", "throw-policy"})
    void summaryAdmissionRejectsUnsafeWorkBeforeSpendingInference(String mode)
            throws Exception
    {
        FakeClient client = summaryClient();
        client.summarySupport = !mode.equals("unsupported");
        var settings = config(client).contextSummarization(context -> {
            if (mode.equals("null-policy")) {
                return null;
            }
            if (mode.equals("throw-policy")) {
                throw new IllegalStateException("private failure");
            }
            return Optional.of(new ContextSummarizationPlan(mode.equals("boundary") ? 1 : 0, 16));
        });
        if (mode.equals("request")) {
            settings.maximumRequests(1);
        }
        if (mode.equals("input")) {
            settings.maximumTextChars(40);
        }
        try (ApiAgentDriver driver = new ApiAgentDriver(settings.build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("original input"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("new input"))).state()).isNotEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(client.requests).hasSize(1);
            assertThat(client.compactions).isEmpty();
            assertThat(readCheckpoint(session.reference()).replay().orElseThrow().payload()).hasSize(8000);
            assertThat(session.resumable()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tokens", "refused", "output-limit", "tools", "blank", "oversize", "stream", "credential", "uncertain"})
    void rejectedSummaryNeverChangesReplayOrExecutesTools(String mode)
            throws Exception
    {
        FakeClient client = summaryClient();
        client.script.add((request, sink) -> {
            assertThat(request.maximumOutputTokens()).isLessThanOrEqualTo(64);
            if (mode.equals("uncertain")) {
                return failed(new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "Summary delivery unknown", true));
            }
            if (mode.equals("stream")) {
                sink.textDelta("s".repeat(65537));
            }
            if (mode.equals("tools")) {
                return completed(toolResponse("dangerous", "{}"));
            }
            var stop = mode.equals("refused") ? ModelResponse.StopReason.REFUSED
                    : mode.equals("output-limit") ? ModelResponse.StopReason.OUTPUT_LIMIT : ModelResponse.StopReason.COMPLETED;
            String text = mode.equals("blank") ? " " : mode.equals("oversize") ? "s".repeat(65537)
                    : mode.equals("credential") ? "secret-summary-key" : "summary";
            return completed(response(text, "isolated", List.of(), stop));
        });
        var settings = config(client).maximumTokens(mode.equals("tokens") ? 5 : 100).redactor(SecretRedactor.fromEnvironment(Map.of("API_KEY", "secret-summary-key")))
                .contextSummarization(context -> Optional.of(new ContextSummarizationPlan(0, 64))).build();
        AgentSessionRef reference;
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            reference = session.reference();
            assertThat(done(session.start(command("original"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("fresh"))).state()).isNotEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(client.requests).hasSize(2);
            assertThat(client.compactions).isEmpty();
            assertThat(readCheckpoint(reference).history().exchanges()).hasSize(1);
            assertThat(readCheckpoint(reference).phase()).isEqualTo(ApiCheckpoint.Phase.READY);
            assertThat(readCheckpoint(reference).replay().orElseThrow().payload()).hasSize(8000);
            assertThat(session.resumable()).isTrue();
        }
        try (ApiAgentDriver resumed = driver(new FakeClient()); AgentSession session = resumed.open(context(Optional.of(reference), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("retry without summary"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledSummaryKeepsWriterUntilModelQuiescesAndDoesNotCommit(boolean deadline)
            throws Exception
    {
        FakeClient client = summaryClient();
        PendingCall pending = new PendingCall(false);
        CountDownLatch submitted = new CountDownLatch(1);
        client.script.add((request, sink) -> {
            assertThat(request.deadline().isPresent()).isEqualTo(deadline);
            submitted.countDown();
            return pending;
        });
        var settings = config(client).shutdownTimeout(Duration.ofMillis(20))
                .contextSummarization(context -> Optional.of(new ContextSummarizationPlan(0, 64))).build();
        ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
        AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
        assertThat(done(session.start(command("original"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        RunningTurn turn = session.start(new AgentTurnCommand("summary", "new input", List.of(), deadline ? Duration.ofMillis(150) : Duration.ZERO));
        try {
            assertThat(submitted.await(1, TimeUnit.SECONDS)).isTrue();
            if (deadline) {
                assertThatThrownBy(() -> turn.completion().toCompletableFuture().get(250, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            }
            else {
                turn.cancel();
            }
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(pending.cancelled).isTrue();
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            assertThatThrownBy(() -> ApiSessionStore.open(directory.resolve("profile"), session.reference().providerValue(), true, settings))
                    .isInstanceOf(AgentUnavailableException.class);
        }
        finally {
            pending.future.complete(response("late summary", "isolated", List.of(), ModelResponse.StopReason.COMPLETED));
            assertThat(done(turn).state()).isEqualTo(deadline ? AgentTurnResult.State.INTERRUPTED : AgentTurnResult.State.CANCELLED);
            session.close();
            driver.close();
        }
        assertThat(client.compactions).isEmpty();
        assertThat(readCheckpoint(session.reference()).replay().orElseThrow().payload()).hasSize(8000);
    }

    @Test
    void summaryPoliciesCanDeclineAndCannotBeCombinedWithLocalCompactors()
            throws Exception
    {
        FakeClient client = summaryClient();
        assertThatThrownBy(() -> config(client).contextSummarization(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config(client).contextCompactor(context -> Optional.empty())
                .contextSummarization(context -> Optional.empty()).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSummarizationPlan(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSummarizationPlan(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ContextSummarizationPlan(2, 32).retainExchanges()).isEqualTo(2);
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).contextSummarization(context -> Optional.empty()).build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("first"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("second"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(client.requests).hasSize(2);
            assertThat(client.compactions).isEmpty();
        }
    }

    private FakeClient summaryClient()
    {
        FakeClient client = new FakeClient();
        client.summarySupport = true;
        client.script.add((request, events) -> completed(response("old answer", "x".repeat(8000), List.of(), ModelResponse.StopReason.COMPLETED)));
        client.compactor = request -> Optional.of(new ModelReplayState("fake-api", 2, "compact"));
        return client;
    }

    @Test
    void failedCompactionSaveBlocksTheLiveSessionAndLeavesTheOriginalCheckpointRecoverable()
            throws Exception
    {
        FakeClient client = new FakeClient();
        client.compactor = request -> Optional.of(new ModelReplayState("fake-api", 2, "compact"));
        client.script.add((request, events) -> completed(response("answer", "x".repeat(8000), List.of(), ModelResponse.StopReason.COMPLETED)));
        ApiSessionRepository.Factory repositories = (profile, id, resume, config) -> {
            ApiSessionRepository store = ApiSessionStore.open(profile, id, resume, config);
            return new ApiSessionRepository()
            {
                @Override
                public ApiCheckpoint state() { return store.state(); }

                @Override
                public void save(ApiCheckpoint next)
                {
                    if (next.replay().filter(replay -> replay.formatVersion() == 2).isPresent()) {
                        throw new IllegalStateException("Injected commit failure");
                    }
                    store.save(next);
                }

                @Override
                public ModelInput.UserMessage stage(String text, List<AgentAttachment> attachments) { return store.stage(text, attachments); }

                @Override
                public void close() { store.close(); }
            };
        };
        AgentSessionRef saved;
        try (var engine = new ApiEngine(config(client).contextCompactor(request -> Optional.of(new ModelCompaction("summary", 0))).build(),
                connectorContext(), repositories, ApiPromptAssembler.DEFAULT);
                AgentSession session = engine.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            saved = session.reference();
            assertThat(done(session.start(command("first"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("second"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(client.requests).hasSize(1);
            assertThat(session.resumable()).isFalse();
            assertThatThrownBy(() -> session.start(command("unsafe retry"))).isInstanceOf(AgentUnavailableException.class);
        }
        FakeClient resumed = new FakeClient();
        try (ApiAgentDriver driver = driver(resumed); AgentSession session = driver.open(context(Optional.of(saved), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("safe retry"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(resumed.requests.getFirst().replayState().orElseThrow().payload()).isEqualTo("x".repeat(8000));
            assertThat(resumed.requests.getFirst().history().orElseThrow().exchanges()).hasSize(1);
        }
    }

    @Test
    void incompleteLegacyHistoryCanContinueButCannotBeCompacted()
            throws Exception
    {
        FakeClient original = new FakeClient();
        original.script.add((request, events) -> completed(response("answer", "x".repeat(8000), List.of(), ModelResponse.StopReason.COMPLETED)));
        AgentSessionRef saved;
        var settings = config(original).build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            saved = session.reference();
            assertThat(done(session.start(command("legacy"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
        try (var store = ApiSessionStore.open(directory.resolve("profile"), saved.providerValue(), true, settings)) {
            ApiCheckpoint state = store.state();
            store.save(new ApiCheckpoint(state.version(), state.id(), state.identity(), state.lane(), state.phase(), state.journal(), state.receipts(),
                    state.replay(), state.setup(), state.input(), new ModelHistory(false, List.of())));
        }
        FakeClient next = new FakeClient();
        AtomicInteger offers = new AtomicInteger();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(next).contextCompactor(request -> {
            assertThat(request.history().complete()).isFalse();
            return offers.getAndIncrement() == 0 ? Optional.empty() : Optional.of(new ModelCompaction("summary", 0));
        }).build(), connectorContext()); AgentSession session = driver.open(context(Optional.of(saved), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("continue"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("compact"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(next.requests).hasSize(1);
            assertThat(next.compactions).isEmpty();
            assertThat(session.resumable()).isTrue();
        }
    }

    @Test
    void identicalCompactionAfterDefinitiveInferenceFailureIsAnIdempotentNoOp()
            throws Exception
    {
        FakeClient client = new FakeClient();
        client.compactor = request -> Optional.of(new ModelReplayState("fake-api", 2, "compact"));
        client.script.add((request, events) -> completed(response("old answer", "x".repeat(8000), List.of(), ModelResponse.StopReason.COMPLETED)));
        client.script.add((request, events) -> failed(new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, "rejected", false)));
        List<EngineObservation> seen = new CopyOnWriteArrayList<>();
        var observer = extensionProvider("metrics", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, seen::add, () -> {}));
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).contextCompactor(request -> Optional.of(new ModelCompaction("summary", 0)))
                .extension(observer, "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("first"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("rejected"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(readCheckpoint(session.reference()).replay().orElseThrow().payload()).isEqualTo("compact");
            assertThat(done(session.start(command("retry"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(seen.stream().filter(EngineObservation.ContextCompacted.class::isInstance)).hasSize(1);
            assertThat(client.compactions).hasSize(2);
            assertThat(client.requests).hasSize(3);
            assertThat(readCheckpoint(session.reference()).history().exchanges()).hasSize(2);
        }
    }

    @Test
    void compactionCommitsReplayBeforeInferenceAndPreservesFullHistoryAndInstructions()
            throws Exception
    {
        FakeClient client = new FakeClient();
        client.script.add((request, events) -> completed(response("old answer", "x".repeat(8000), List.of(), ModelResponse.StopReason.COMPLETED)));
        List<EngineObservation> seen = new CopyOnWriteArrayList<>();
        AtomicInteger proposals = new AtomicInteger();
        client.compactor = request -> {
            assertThat(request.history().exchanges()).hasSize(1);
            assertThat(request.replay().payload()).hasSize(8000);
            assertThat(request.compaction().summary()).isEqualTo("private summary");
            return Optional.of(new ModelReplayState("fake-api", 2, "compact"));
        };
        ContextCompactor policy = request -> {
            proposals.incrementAndGet();
            assertThat(request.turnId()).isEqualTo("turn");
            assertThat(request.model()).isEqualTo("model");
            assertThat(request.deadline()).isEmpty();
            assertThat(request.cancelled()).isFalse();
            assertThat(request.replayCharacters()).isEqualTo(8000);
            return Optional.of(new ModelCompaction("private summary", 0));
        };
        var observer = extensionProvider("metrics", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, seen::add, () -> {}));
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).contextCompactor(policy).extension(observer, "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(proposals).hasValue(0);
            assertThat(done(session.start(command("original input"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(proposals).hasValue(0);
            client.script.add((request, events) -> {
                assertThat(request.history().orElseThrow().exchanges().getFirst().input()).isEqualTo(new ModelInput.UserMessage("original input", List.of()));
                assertThat(request.replayState().orElseThrow().payload()).isEqualTo("compact");
                assertThat(request.instructions()).isEqualTo("\nHost instructions");
                assertThat(readCheckpoint(session.reference()).replay().orElseThrow().formatVersion()).isEqualTo(2);
                return answer(request, events);
            });
            assertThat(done(session.start(command("next input"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(proposals).hasValue(1);
            assertThat(client.requests).hasSize(2);
            assertThat(readCheckpoint(session.reference()).history().exchanges()).hasSize(2);
            assertThat(seen.stream().filter(EngineObservation.ContextCompacted.class::isInstance)).hasSize(1);
            assertThat(seen.toString()).doesNotContain("private summary", "original input");
        }
        assertThatThrownBy(() -> config(new FakeClient()).contextCompactor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void invalidOrUnsupportedCompactionKeepsOriginalReplayAndPerformsNoFreshInference()
            throws Exception
    {
        for (String mode : List.of("policy-null", "policy-throw", "unsupported", "adapter-null", "foreign", "larger", "throw")) {
            FakeClient client = new FakeClient();
            client.script.add((request, events) -> completed(response("answer", "x".repeat(8000), List.of(), ModelResponse.StopReason.COMPLETED)));
            AtomicInteger attempts = new AtomicInteger();
            if (!mode.equals("unsupported")) {
                client.compactor = request -> switch (mode) {
                    case "adapter-null" -> null;
                    case "foreign" -> Optional.of(new ModelReplayState("foreign", 1, "compact"));
                    case "larger" -> Optional.of(new ModelReplayState("fake-api", 2, "x".repeat(8001)));
                    case "throw" -> throw new IllegalStateException("private diagnostic");
                    default -> Optional.of(new ModelReplayState("fake-api", 2, "compact"));
                };
            }
            ContextCompactor policy = request -> {
                if (attempts.getAndIncrement() > 0) {
                    return Optional.empty();
                }
                if (mode.equals("policy-throw")) {
                    throw new IllegalStateException("private diagnostic");
                }
                return mode.equals("policy-null") ? null : Optional.of(new ModelCompaction("summary", 0));
            };
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).contextCompactor(policy).build(), connectorContext());
                    AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
                assertThat(done(session.start(command("first"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                AgentTurnResult result = done(session.start(command("second")));
                assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
                assertThat(result.toString()).doesNotContain("private diagnostic");
                assertThat(client.requests).hasSize(1);
                assertThat(readCheckpoint(session.reference()).replay().orElseThrow().payload()).isEqualTo("x".repeat(8000));
                assertThat(session.resumable()).isTrue();
                assertThat(done(session.start(command("retry"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(client.requests.getLast().replayState().orElseThrow().formatVersion()).isEqualTo(1);
            }
        }
    }

    @Test
    void emptyCompactionProposalLeavesNativeReplayAndCallbacksUnchanged()
            throws Exception
    {
        FakeClient client = new FakeClient();
        AtomicInteger proposals = new AtomicInteger();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).contextCompactor(request -> {
            proposals.incrementAndGet();
            return Optional.empty();
        }).build(), connectorContext()); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("one"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("two"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(proposals).hasValue(1);
            assertThat(client.compactions).isEmpty();
            assertThat(client.requests.getLast().replayState().orElseThrow().formatVersion()).isEqualTo(1);
        }
    }

    @Test
    void noncooperativeCompactionRetainsWriterAndCapacityUntilCallbackReturns()
            throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeClient client = new FakeClient();
        var settings = config(client).shutdownTimeout(Duration.ofMillis(30)).contextCompactor(request -> {
            entered.countDown();
            boolean stopped = false;
            while (!stopped) {
                try {
                    release.await();
                    stopped = true;
                }
                catch (InterruptedException ignored) {
                    assertThat(request.cancelled()).isTrue();
                }
            }
            return Optional.empty();
        }).build();
        ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
        AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
        assertThat(done(session.start(command("first"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        var before = readCheckpoint(session.reference()).replay().orElseThrow().payload();
        RunningTurn turn = session.start(command("second"));
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            turn.cancel();
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            assertThatThrownBy(() -> ApiSessionStore.open(directory.resolve("profile"), session.reference().providerValue(), true, settings))
                    .isInstanceOf(AgentUnavailableException.class);
            assertThat(client.requests).hasSize(1);
        }
        finally {
            release.countDown();
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            session.close();
            driver.close();
        }
        assertThat(readCheckpoint(session.reference()).replay().orElseThrow().payload()).isEqualTo(before);
    }

    @Test
    void oneBorrowedBudgetBoundsToolsAcrossIndependentDriversAndCancelledWaitersDoNotStart()
            throws Exception
    {
        var shared = ToolExecutionBudget.bounded(1);
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondWaiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolExecutionBudget budget = request -> {
            if (acquired.incrementAndGet() == 2) {
                secondWaiting.countDown();
            }
            assertThat(Thread.currentThread().getName()).startsWith("codej-api-worker-");
            return shared.acquire(request);
        };
        AtomicInteger executed = new AtomicInteger();
        Tool firstTool = Tool.withSchema("lookup", "Lookup", "{}", args -> {
            executed.incrementAndGet();
            firstEntered.countDown();
            release.await();
            return "first";
        });
        Tool secondTool = Tool.withSchema("lookup", "Lookup", "{}", args -> {
            executed.incrementAndGet();
            return "second";
        });
        FakeClient firstClient = new FakeClient();
        FakeClient secondClient = new FakeClient();
        firstClient.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        secondClient.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        try (ApiAgentDriver firstDriver = new ApiAgentDriver(config(firstClient).toolExecutionBudget(budget).build(), connectorContext());
                ApiAgentDriver secondDriver = new ApiAgentDriver(config(secondClient).toolExecutionBudget(budget).build(), connectorContext());
                AgentSession first = firstDriver.open(context(Optional.empty(), List.of(firstTool), allow()), event -> {});
                AgentSession second = secondDriver.open(context(Optional.empty(), List.of(secondTool), allow()), event -> {})) {
            RunningTurn firstTurn = first.start(command("First"));
            try {
                assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
                RunningTurn secondTurn = second.start(command("Second"));
                assertThat(secondWaiting.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(executed).hasValue(1);
                secondTurn.cancel();
                assertThat(done(secondTurn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
                assertThat(readCheckpoint(second.reference()).receipts().getFirst().started()).isFalse();
                assertThat(executed).hasValue(1);
            }
            finally {
                release.countDown();
            }
            assertThat(done(firstTurn).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            secondClient.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
            try (AgentSession fresh = secondDriver.open(context(Optional.empty(), List.of(secondTool), allow()), event -> {})) {
                assertThat(done(fresh.start(command("Fresh"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            }
            assertThat(executed).hasValue(2);
        }
    }

    @Test
    void deadlineWhileWaitingForSharedCapacityLeavesTheReceiptUnstarted()
            throws Exception
    {
        var budget = ToolExecutionBudget.bounded(1);
        try (var held = budget.acquire(budgetContext(Optional.empty()))) {
            FakeClient client = new FakeClient();
            client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
            Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> { throw new AssertionError("No capacity available"); });
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).toolExecutionBudget(budget).build(), connectorContext());
                    AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
                assertThat(done(session.start(new AgentTurnCommand("turn", "Hello", List.of(), Duration.ofMillis(500)))).state())
                        .isEqualTo(AgentTurnResult.State.INTERRUPTED);
                assertThat(readCheckpoint(session.reference()).receipts().getFirst().started()).isFalse();
            }
        }
    }

    @Test
    void cancellationCannotReleaseSharedCapacityUntilAnExecutingCallbackReallyStops()
            throws Exception
    {
        var budget = ToolExecutionBudget.bounded(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                }
                catch (InterruptedException ignored) {
                }
            }
            return "observed success";
        });
        FakeClient client = new FakeClient();
        client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        ApiAgentDriver driver = new ApiAgentDriver(config(client).shutdownTimeout(Duration.ofMillis(30)).toolExecutionBudget(budget).build(), connectorContext());
        AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {});
        RunningTurn turn = session.start(command("Hello"));
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            turn.cancel();
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(turn.completion().toCompletableFuture()).isNotDone();
            assertThatThrownBy(() -> budget.acquire(budgetContext(Optional.of(Instant.now().plusMillis(25)))))
                    .isInstanceOf(CancellationException.class);
        }
        finally {
            release.countDown();
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            session.close();
            driver.close();
        }
        try (var reclaimed = budget.acquire(budgetContext(Optional.empty()))) {
            assertThat(readCheckpoint(session.reference()).receipts().getFirst().result().orElseThrow().text()).isEqualTo("observed success");
        }
    }

    private static ToolExecutionBudget.Context budgetContext(Optional<Instant> deadline)
    {
        return new ToolExecutionBudget.Context()
        {
            @Override
            public String invocationId() { return "external"; }

            @Override
            public Optional<Instant> deadline() { return deadline; }

            @Override
            public boolean cancellationRequested() { return false; }
        };
    }

    @Test
    void scopedExtensionsApplyPoliciesAndObserveDurableOutcomesInOrder()
            throws Exception
    {
        List<String> trace = new CopyOnWriteArrayList<>();
        List<EngineObservation> observations = new CopyOnWriteArrayList<>();
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        var provider = extensionProvider("rules", (setting, scope) -> {
            opened.incrementAndGet();
            assertThat(setting).isEqualTo("configuration");
            assertThat(scope.identity()).isEqualTo(new ConnectorIdentity("fake-api", "work"));
            assertThat(scope.worktree()).isEqualTo(directory);
            assertThat(scope.access()).isEqualTo(SessionAccess.TOOLS_ONLY);
            assertThat(scope.shutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
            return extension(request -> {
                assertThat(request.turnId()).isEqualTo("turn");
                assertThat(request.invocationId()).isNotBlank();
                assertThat(request.toolName()).isEqualTo("lookup");
                assertThat(request.requirements().capabilities()).contains(ToolCapability.HOST_INVOKE);
                assertThat(request.deadline()).isEmpty();
                assertThat(request.cancelled()).isFalse();
                trace.add("policy");
                return ToolPolicy.Decision.CONTINUE;
            }, event -> {
                observations.add(event);
                if (event instanceof EngineObservation.ToolCompleted) {
                    assertThat(executions).hasValue(1);
                    assertThat(readCheckpoint(scope.reference()).receipts().getFirst().result()).isPresent();
                }
            }, () -> trace.add("close"));
        });
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> {
            trace.add("execute");
            executions.incrementAndGet();
            return "private tool output";
        });
        FakeClient client = new FakeClient();
        client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        ApiEngineConfig settings = config(client).extension(provider, "configuration").build();
        assertThat(opened).hasValue(0);
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), (request, timeout) -> {
                    trace.add("approval");
                    approvals.incrementAndGet();
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                }), event -> {})) {
            assertThat(done(session.start(command("private user input"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(observations).extracting(Object::getClass).containsExactly(EngineObservation.TurnStarted.class, EngineObservation.ModelCompleted.class,
                    EngineObservation.ToolDecision.class, EngineObservation.ToolCompleted.class, EngineObservation.ModelCompleted.class, EngineObservation.TurnFinished.class);
            assertThat(observations).allMatch(event -> event.turnId().equals("turn"));
            assertThat(observations.toString()).doesNotContain("private user input", "private tool output", "after-call", "Host instructions");
        }
        assertThat(opened).hasValue(1);
        assertThat(approvals).hasValue(1);
        assertThat(trace).containsExactly("policy", "approval", "execute", "close");
    }

    @Test
    void denyingNullAndThrowingPoliciesFailClosedBeforeApprovalAndAdmission()
            throws Exception
    {
        for (String mode : List.of("deny", "null", "throw")) {
            FakeClient client = new FakeClient();
            client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
            List<String> trace = new CopyOnWriteArrayList<>();
            List<EngineObservation> observations = new CopyOnWriteArrayList<>();
            var first = extensionProvider("first", (setting, scope) -> extension(request -> {
                trace.add("first");
                return ToolPolicy.Decision.CONTINUE;
            }, observations::add, () -> {}));
            var second = extensionProvider("second", (setting, scope) -> extension(request -> {
                trace.add("second");
                return switch (mode) {
                    case "deny" -> ToolPolicy.Decision.DENY;
                    case "null" -> null;
                    default -> throw new IllegalStateException("private policy diagnostic");
                };
            }, event -> {}, () -> {}));
            var third = extensionProvider("third", (setting, scope) -> extension(request -> {
                throw new AssertionError("Policies after denial must not run");
            }, event -> {}, () -> {}));
            Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> { throw new AssertionError("Denied tool must not run"); });
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(first, "one").extension(second, "two").extension(third, "three")
                    .toolExecutionBudget(request -> { throw new AssertionError("Denied calls consume no capacity"); }).build(), connectorContext());
                    AgentSession session = driver.open(context(Optional.empty(), List.of(tool), (request, timeout) -> {
                        throw new AssertionError("Denied invocation must not prompt");
                    }), event -> {})) {
                assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                var outcome = ((ModelInput.ToolResults) client.requests.getLast().input()).results().getFirst();
                assertThat(outcome.error()).isTrue();
                assertThat(outcome.text()).contains("rejected by policy").doesNotContain("private policy diagnostic");
                assertThat(trace).containsExactly("first", "second");
                assertThat(observations.stream().filter(EngineObservation.ToolDecision.class::isInstance).map(EngineObservation.ToolDecision.class::cast))
                        .extracting(EngineObservation.ToolDecision::admission).containsExactly(mode.equals("deny")
                                ? EngineObservation.Admission.POLICY_DENIED : EngineObservation.Admission.POLICY_UNAVAILABLE);
            }
        }
    }

    @Test
    void continueDoesNotBypassApprovalsAndInvalidArgumentsDoNotReachPolicy()
            throws Exception
    {
        for (boolean invalid : List.of(true, false)) {
            AtomicInteger evaluated = new AtomicInteger();
            AtomicInteger asked = new AtomicInteger();
            var provider = extensionProvider("rules", (setting, scope) -> extension(request -> {
                evaluated.incrementAndGet();
                return ToolPolicy.Decision.CONTINUE;
            }, event -> {}, () -> {}));
            FakeClient client = new FakeClient();
            client.script.add((request, events) -> completed(toolResponse("lookup", invalid ? "{}" : "{\"key\":\"value\"}")));
            Tool tool = Tool.of("lookup", "Lookup", Issue.class, args -> { throw new AssertionError("Denied tool must not run"); });
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(provider, "value").build(), connectorContext());
                    AgentSession session = driver.open(context(Optional.empty(), List.of(tool), (request, timeout) -> {
                        asked.incrementAndGet();
                        return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                    }), event -> {})) {
                assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(evaluated).hasValue(invalid ? 0 : 1);
                assertThat(asked).hasValue(invalid ? 0 : 1);
            }
        }
    }

    @Test
    void observerFailureIsDisabledWithoutDisablingPolicyOrOtherObservers()
            throws Exception
    {
        AtomicInteger broken = new AtomicInteger();
        AtomicInteger evaluated = new AtomicInteger();
        List<EngineObservation> seen = new CopyOnWriteArrayList<>();
        var one = extensionProvider("broken", (setting, scope) -> extension(request -> {
            evaluated.incrementAndGet();
            return ToolPolicy.Decision.DENY;
        }, event -> {
            broken.incrementAndGet();
            throw new IllegalStateException("observer private diagnostic");
        }, () -> {}));
        var two = extensionProvider("healthy", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, seen::add, () -> {}));
        FakeClient client = new FakeClient();
        client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> { throw new AssertionError("Policy remains active"); });
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(one, "one").extension(two, "two").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
            assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("Again"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(broken).hasValue(1);
            assertThat(evaluated).hasValue(1);
            assertThat(seen.stream().filter(EngineObservation.TurnFinished.class::isInstance)).hasSize(2);
        }
    }

    @Test
    void observersReceiveFailedAndCancelledExecutionOutcomesAndSanitizedMetadata()
            throws Exception
    {
        List<EngineObservation> seen = new CopyOnWriteArrayList<>();
        var provider = extensionProvider("metrics", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, seen::add, () -> {}));
        FakeClient client = new FakeClient();
        client.script.add((request, events) -> failed(new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, "private diagnostic", false)));
        PendingCall pending = new PendingCall(true);
        CountDownLatch entered = new CountDownLatch(1);
        client.script.add((request, events) -> {
            entered.countDown();
            return pending;
        });
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).redactor(SecretRedactor.fromEnvironment(Map.of("API_KEY", "secret-key")))
                .extension(provider, "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(new AgentTurnCommand("secret-key", "private input", List.of(), Duration.ZERO))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            RunningTurn turn = session.start(command("Again"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            turn.cancel();
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            assertThat(seen.stream().filter(EngineObservation.TurnFinished.class::isInstance).map(EngineObservation.TurnFinished.class::cast))
                    .extracting(EngineObservation.TurnFinished::state).containsExactly(AgentTurnResult.State.FAILED, AgentTurnResult.State.CANCELLED);
            assertThat(seen.toString()).doesNotContain("secret-key", "private diagnostic", "private input").contains("[REDACTED]");
        }
    }

    @Test
    void extensionOpenFailureClosesAcquiredResourcesInReverseOrder()
    {
        List<String> trace = new ArrayList<>();
        AtomicReference<AgentSessionRef> reference = new AtomicReference<>();
        var first = extensionProvider("first", (setting, scope) -> {
            reference.set(scope.reference());
            return extension(request -> ToolPolicy.Decision.CONTINUE, event -> {}, () -> trace.add("first"));
        });
        var second = extensionProvider("second", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, event -> {}, () -> trace.add("second")));
        var bad = extensionProvider("bad", (setting, scope) -> { throw new IllegalStateException("private open diagnostic"); });
        FakeClient client = new FakeClient();
        var settings = config(client).extension(first, "one").extension(second, "two").extension(bad, "three").build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext())) {
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class).hasMessageNotContaining("private open diagnostic");
            assertThat(trace).containsExactly("second", "first");
            try (ApiSessionStore ignored = ApiSessionStore.open(directory.resolve("profile"), reference.get().providerValue(), true, settings)) {
                assertThat(ignored.state().phase()).isEqualTo(ApiCheckpoint.Phase.BLOCKED);
            }
        }
    }

    @Test
    void extensionCleanupFailureRetainsOwnershipAndRetriesOnlyUnclosedScopes()
    {
        List<String> trace = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        var first = extensionProvider("first", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, event -> {}, () -> trace.add("first")));
        var second = extensionProvider("second", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, event -> {}, () -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("not stopped");
            }
            trace.add("second");
        }));
        var third = extensionProvider("third", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, event -> {}, () -> trace.add("third")));
        FakeClient client = new FakeClient();
        var settings = config(client).maximumSessions(1).extension(first, "one").extension(second, "two").extension(third, "three").build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext())) {
            AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
            try {
                assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
                assertThat(trace).containsExactly("third");
                assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {})).hasMessageContaining("capacity");
                assertThatThrownBy(() -> ApiSessionStore.open(directory.resolve("profile"), session.reference().providerValue(), true, settings))
                        .isInstanceOf(AgentUnavailableException.class);
            }
            finally {
                session.close();
            }
            assertThat(trace).containsExactly("third", "second", "first");
        }
    }

    @Test
    void blockedPolicyAndObserverRetainOwnershipUntilCallbacksReallyReturn()
            throws Exception
    {
        for (boolean policy : List.of(true, false)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger closed = new AtomicInteger();
            Runnable block = () -> {
                entered.countDown();
                boolean stopped = false;
                while (!stopped) {
                    try {
                        release.await();
                        stopped = true;
                    }
                    catch (InterruptedException ignored) {
                    }
                }
            };
            var provider = extensionProvider("blocked", (setting, scope) -> extension(request -> {
                if (policy) {
                    block.run();
                    assertThat(request.cancelled()).isTrue();
                }
                return ToolPolicy.Decision.CONTINUE;
            }, event -> {
                if (!policy && event instanceof EngineObservation.TurnStarted) {
                    block.run();
                }
            }, closed::incrementAndGet));
            FakeClient client = new FakeClient();
            client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
            Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> { throw new AssertionError("Cancelled tool must not run"); });
            var settings = config(client).shutdownTimeout(Duration.ofMillis(30)).extension(provider, "value").build();
            ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
            AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {});
            RunningTurn turn = session.start(command("Hello"));
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                turn.cancel();
                assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class);
                assertThat(turn.completion().toCompletableFuture()).isNotDone();
                assertThat(closed).hasValue(0);
                assertThatThrownBy(() -> ApiSessionStore.open(directory.resolve("profile"), session.reference().providerValue(), true, settings))
                        .isInstanceOf(AgentUnavailableException.class);
            }
            finally {
                release.countDown();
                assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
                session.close();
                driver.close();
            }
            assertThat(closed).hasValue(1);
        }
    }

    @Test
    void nullExtensionAndDuplicateIdsFailBeforeInference()
    {
        AtomicInteger opened = new AtomicInteger();
        var provider = extensionProvider("null-source", (setting, scope) -> {
            opened.incrementAndGet();
            return null;
        });
        FakeClient client = new FakeClient();
        assertThatThrownBy(() -> config(client).extension(provider, "one").extension(provider, "two").build()).isInstanceOf(IllegalArgumentException.class);
        assertThat(opened).hasValue(0);
        assertThat(directory.resolve("profile")).doesNotExist();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(provider, "value").build(), connectorContext())) {
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {})).isInstanceOf(AgentUnavailableException.class);
            assertThat(client.requests).isEmpty();
        }
        assertThat(opened).hasValue(1);
    }

    @Test
    void observerStateAndCleanupAreIndependentAcrossSessions()
            throws Exception
    {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger notified = new AtomicInteger();
        var provider = extensionProvider("per-session", (setting, scope) -> {
            opened.incrementAndGet();
            return extension(request -> ToolPolicy.Decision.CONTINUE, event -> {
                notified.incrementAndGet();
                throw new IllegalStateException("disable only this session");
            }, closed::incrementAndGet);
        });
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(provider, "value").build(), connectorContext());
                AgentSession first = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
                AgentSession second = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(first.reference()).isNotEqualTo(second.reference());
            assertThat(done(first.start(command("First"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(first.start(command("Again"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(second.start(command("Second"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
        assertThat(opened).hasValue(2);
        assertThat(notified).hasValue(2);
        assertThat(closed).hasValue(2);
    }

    @Test
    void reentrantExtensionOpenCannotReleaseInitializingResources()
    {
        AtomicReference<ApiAgentDriver> holder = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        var provider = extensionProvider("reentrant", (setting, scope) -> {
            assertThatThrownBy(holder.get()::close).isInstanceOf(AgentUnavailableException.class);
            return extension(request -> ToolPolicy.Decision.CONTINUE, event -> {}, closed::incrementAndGet);
        });
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(provider, "value").build(), connectorContext())) {
            holder.set(driver);
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {})).isInstanceOf(AgentUnavailableException.class);
            assertThat(closed).hasValue(1);
        }
    }

    @Test
    void terminalObserverErrorsDoNotPreventCompletionOrRelease()
            throws Exception
    {
        AtomicInteger closed = new AtomicInteger();
        var provider = extensionProvider("broken-terminal", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, event -> {
            if (event instanceof EngineObservation.TurnFinished) {
                throw new AssertionError("private observer failure");
            }
        }, closed::incrementAndGet));
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(provider, "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(session.start(command("Again"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(session.resumable()).isTrue();
        }
        assertThat(closed).hasValue(1);
    }

    @Test
    void deadlineRemainsActiveThroughTheTerminalObserverCallback()
            throws Exception
    {
        AtomicInteger interrupted = new AtomicInteger();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var provider = extensionProvider("terminal-deadline", (setting, scope) -> extension(request -> ToolPolicy.Decision.CONTINUE, event -> {
            if (event instanceof EngineObservation.TurnFinished) {
                blocked.countDown();
                try {
                    release.await();
                }
                catch (InterruptedException failure) {
                    interrupted.incrementAndGet();
                }
            }
        }, () -> {}));
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).extension(provider, "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            RunningTurn turn = session.start(new AgentTurnCommand("turn", "Hello", List.of(), Duration.ofMillis(500)));
            try {
                assertThat(blocked.await(1, TimeUnit.SECONDS)).isTrue();
                // The execution outcome is already selected; the callback must still receive the deadline interrupt.
                assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(interrupted).hasValue(1);
            }
            finally {
                release.countDown();
            }
        }
    }

    private static EngineExtensionProvider<String> extensionProvider(String id, BiFunction<String, EngineExtensionContext, EngineExtension> open)
    {
        return new EngineExtensionProvider<>()
        {
            @Override
            public String id() { return id; }

            @Override
            public EngineExtension open(String setting, EngineExtensionContext context) { return open.apply(setting, context); }
        };
    }

    private static EngineExtension extension(java.util.function.Function<ToolPolicyContext, ToolPolicy.Decision> policy,
            java.util.function.Consumer<EngineObservation> observer, Runnable close)
    {
        return new EngineExtension()
        {
            @Override
            public Decision evaluate(ToolPolicyContext context) { return policy.apply(context); }

            @Override
            public void observe(EngineObservation observation) { observer.accept(observation); }

            @Override
            public void close() { close.run(); }
        };
    }

    @Test
    void promptSourcesAreSessionOwnedAndCaptureOncePerTurnAcrossToolContinuation()
            throws Exception
    {
        FakeClient client = new FakeClient();
        client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger tools = new AtomicInteger();
        PromptProvider<String> provider = promptProvider("workspace", (setting, scope) -> {
            opened.incrementAndGet();
            assertThat(scope.identity()).isEqualTo(new ConnectorIdentity("fake-api", "work"));
            assertThat(scope.worktree()).isEqualTo(directory);
            assertThat(scope.reference().laneId()).isEqualTo(new AgentLaneId("fake-api-work"));
            assertThat(scope.access()).isIn(SessionAccess.TEXT_ONLY, SessionAccess.TOOLS_ONLY);
            assertThat(scope.shutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
            String identity = scope.reference().providerValue();
            AtomicInteger local = new AtomicInteger();
            return promptContributor(request -> {
                captured.incrementAndGet();
                int version = local.incrementAndGet();
                assertThat(request.turnId()).isEqualTo("turn");
                assertThat(request.model()).isEqualTo("model");
                assertThat(request.history().complete()).isTrue();
                assertThat(request.cancelled()).isFalse();
                if (version == 1 && request.input().text().equals("Hello")) {
                    assertThat(request.effort()).contains("high");
                    assertThat(request.deadline()).isPresent();
                }
                return contribution("Rules " + identity + "/" + version, setting + " " + identity + "/" + version);
            }, closed::incrementAndGet);
        });
        Tool lookup = Tool.withSchema("lookup", "Lookup", "{}", args -> {
            tools.incrementAndGet();
            return "Found";
        });
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).promptProvider(provider, "Facts").build(), connectorContext());
                AgentSession first = driver.open(context(Optional.empty(), List.of(lookup), allow()), event -> {});
                AgentSession second = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(opened).hasValue(2);
            assertThat(done(first.start(new AgentTurnCommand("turn", "Hello", List.of(), Duration.ofSeconds(2), Optional.empty(), Optional.of("high")))).state())
                    .isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(captured).hasValue(1);
            assertThat(client.requests.get(0).instructions()).isEqualTo(client.requests.get(1).instructions());
            assertThat(client.requests.get(1).input()).isInstanceOf(ModelInput.ToolResults.class);
            assertThat(done(second.start(command("Other"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(done(first.start(command("Again"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            String firstId = first.reference().providerValue();
            String secondId = second.reference().providerValue();
            assertThat(((ModelInput.UserMessage) client.requests.get(0).input()).text()).contains(firstId + "/1").endsWith("Hello");
            assertThat(((ModelInput.UserMessage) client.requests.get(2).input()).text()).contains(secondId + "/1").doesNotContain(firstId);
            assertThat(((ModelInput.UserMessage) client.requests.get(3).input()).text()).contains(firstId + "/2").doesNotContain(secondId);
            assertThat(client.requests.get(3).history().orElseThrow().exchanges().getFirst().input()).isEqualTo(client.requests.getFirst().input());
            assertThat(tools).hasValue(1);
            assertThat(captured).hasValue(3);
        }
        assertThat(closed).hasValue(2);
    }

    @Test
    void promptProviderOrderAndNamespacedSectionsAreDeterministicAndEmptySourcesKeepLegacyBytes()
            throws Exception
    {
        FakeClient client = new FakeClient();
        var first = promptProvider("one", (setting, scope) -> request -> contribution("Rules1", "Facts1"));
        var second = promptProvider("two", (setting, scope) -> request -> contribution("Rules2", "Facts2"));
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).promptProvider(first, "one")
                .promptProvider(PromptProviderBinding.of(second, "two")).build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(client.requests.getFirst().instructions()).isEqualTo("\nHost instructions\n\n[Instruction one/rules]\nRules1\n\n[Instruction two/rules]\nRules2");
            assertThat(((ModelInput.UserMessage) client.requests.getFirst().input()).text())
                    .isEqualTo("[Context one/facts]\nFacts1\n[/Context]\n\n[Context two/facts]\nFacts2\n[/Context]\n\nHello");
        }
        FakeClient empty = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(empty)
                .promptProvider(promptProvider("empty", (setting, scope) -> request -> PromptContribution.empty()), "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(empty.requests.getFirst().instructions()).isEqualTo("\nHost instructions");
            assertThat(empty.requests.getFirst().input()).isEqualTo(new ModelInput.UserMessage("Hello", List.of()));
        }
    }

    @Test
    void invalidPromptAssemblyAndAggregateBudgetsFailBeforeInferenceWithoutDamagingHistory()
            throws Exception
    {
        List<java.util.function.Function<PromptContext, PromptContribution>> bad = List.of(
                request -> null,
                request -> { throw new IllegalStateException("private-configuration-secret"); },
                request -> new PromptContribution(List.of(new PromptSection("large", "x".repeat(PromptContribution.MAX_CHARACTERS))), List.of()),
                request -> new PromptContribution(List.of(), List.of(new PromptSection("facts", "x".repeat(PromptContribution.MAX_CHARACTERS)))));
        for (var failure : bad) {
            FakeClient client = new FakeClient();
            AtomicInteger attempts = new AtomicInteger();
            var provider = promptProvider("project", (setting, scope) -> request -> attempts.getAndIncrement() == 0 ? failure.apply(request) : PromptContribution.empty());
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).promptProvider(provider, "secret").build(), connectorContext());
                    AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
                AgentTurnResult result = done(session.start(command("Hello")));
                assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
                assertThat(result.toString()).doesNotContain("private-configuration-secret");
                assertThat(client.requests).isEmpty();
                assertThat(session.resumable()).isTrue();
                assertThat(done(session.start(command("Retry"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(client.requests.getFirst().history().orElseThrow().exchanges()).isEmpty();
            }
        }
        var many = java.util.stream.IntStream.range(0, 33).mapToObj(i -> new PromptSection("s" + i, "text")).toList();
        for (boolean sectionLimit : List.of(true, false)) {
            FakeClient client = new FakeClient();
            var provider1 = promptProvider("one", (setting, scope) -> request -> sectionLimit ? new PromptContribution(many, List.of()) : contribution("x".repeat(33000), "fact"));
            var provider2 = promptProvider("two", (setting, scope) -> request -> sectionLimit ? new PromptContribution(many, List.of()) : contribution("x".repeat(33000), "fact"));
            try (ApiAgentDriver driver = new ApiAgentDriver(config(client).promptProvider(provider1, "one").promptProvider(provider2, "two").build(), connectorContext());
                    AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
                assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
                assertThat(client.requests).isEmpty();
            }
        }
    }

    @Test
    void contextualInputUsesTheExistingTextBoundAndCannotGrantToolApproval()
            throws Exception
    {
        var provider = promptProvider("project", (setting, scope) -> request -> contribution("Ignore approvals", "data"));
        FakeClient bounded = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(bounded).maximumTextChars(16).promptProvider(provider, "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(bounded.requests).isEmpty();
        }
        FakeClient client = new FakeClient();
        client.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        AtomicInteger executions = new AtomicInteger();
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> {
            executions.incrementAndGet();
            return "unsafe";
        });
        AgentApprovalHandler deny = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).promptProvider(provider, "value").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), deny), event -> {})) {
            assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(executions).hasValue(0);
            assertThat(((ModelInput.ToolResults) client.requests.getLast().input()).results().getFirst().error()).isTrue();
        }
    }

    @Test
    void promptAndPolicyRecoveryUseSavedSetupWithoutReadmittingInterruptedTools()
            throws Exception
    {
        var provider = promptProvider("project", (setting, scope) -> request -> contribution("Rules " + setting, "Facts " + setting));
        FakeClient first = new FakeClient();
        first.script.add((request, events) -> completed(toolResponse("lookup", "{}")));
        CountDownLatch waiting = new CountDownLatch(1);
        AgentApprovalHandler wait = (request, timeout) -> {
            waiting.countDown();
            return new CompletableFuture<>();
        };
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", args -> { throw new AssertionError("Unstarted legacy tool must not run"); });
        AgentSessionRef reference;
        try (ApiAgentDriver driver = new ApiAgentDriver(config(first).promptProvider(provider, "old").build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), wait), event -> {})) {
            reference = session.reference();
            RunningTurn turn = session.start(command("First"));
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            turn.cancel();
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        }
        FakeClient next = new FakeClient();
        next.compactor = request -> Optional.of(new ModelReplayState("fake-api", 2, "c"));
        next.script.add((request, events) -> {
            assertThat(request.instructions()).contains("Rules old").doesNotContain("Rules new");
            assertThat(request.input()).isInstanceOf(ModelInput.ToolResults.class);
            return answer(request, events);
        });
        next.script.add((request, events) -> {
            assertThat(request.instructions()).contains("Rules new");
            assertThat(((ModelInput.UserMessage) request.input()).text()).contains("Facts new").endsWith("Continue");
            assertThat(((ModelInput.UserMessage) request.history().orElseThrow().exchanges().getFirst().input()).text()).contains("Facts old");
            return answer(request, events);
        });
        List<EngineObservation> observations = new CopyOnWriteArrayList<>();
        var observer = extensionProvider("recovery", (setting, scope) -> extension(request -> {
            throw new AssertionError("Interrupted tools must not be readmitted by a new policy");
        }, observations::add, () -> {}));
        ContextCompactor compactor = request -> {
            assertThat(request.history().exchanges()).hasSize(2);
            assertThat(request.history().exchanges().getLast().toolCalls()).isEmpty();
            return Optional.of(new ModelCompaction("summary", 0));
        };
        var settings = config(next).promptProvider(provider, "new").extension(observer, "value").contextCompactor(compactor).build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
                AgentSession session = driver.open(context(Optional.of(reference), List.of(tool), allow()), event -> {})) {
            assertThat(done(session.start(command("Continue"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(next.requests).hasSize(2);
            assertThat(next.compactions).hasSize(1);
            assertThat(observations.stream().filter(EngineObservation.ModelCompleted.class::isInstance)).hasSize(2);
            assertThat(observations).noneMatch(event -> event instanceof EngineObservation.ToolDecision || event instanceof EngineObservation.ToolCompleted);
        }
    }

    @Test
    void promptOpenFailureClosesReturnedSourcesInReverseOrderAndReleasesTheWriter()
    {
        List<String> trace = new ArrayList<>();
        AtomicReference<AgentSessionRef> reference = new AtomicReference<>();
        var one = promptProvider("one", (setting, scope) -> {
            reference.set(scope.reference());
            return promptContributor(request -> PromptContribution.empty(), () -> trace.add("one"));
        });
        var two = promptProvider("two", (setting, scope) -> promptContributor(request -> PromptContribution.empty(), () -> trace.add("two")));
        var bad = promptProvider("bad", (setting, scope) -> { throw new IllegalStateException("private secret"); });
        FakeClient client = new FakeClient();
        ApiEngineConfig settings = config(client).promptProvider(one, "value").promptProvider(two, "value").promptProvider(bad, "value").build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext())) {
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {})).isInstanceOf(AgentUnavailableException.class)
                    .hasMessageNotContaining("private secret");
            assertThat(trace).containsExactly("two", "one");
            try (ApiSessionStore ignored = ApiSessionStore.open(directory.resolve("profile"), reference.get().providerValue(), true, settings)) {
                assertThat(ignored.state().phase()).isEqualTo(ApiCheckpoint.Phase.BLOCKED);
            }
        }
    }

    @Test
    void promptCloseFailureRetainsOwnershipUntilCleanupCanBeRetried()
    {
        AtomicInteger closed = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean refuse = new java.util.concurrent.atomic.AtomicBoolean(true);
        var provider = promptProvider("project", (setting, scope) -> promptContributor(request -> PromptContribution.empty(), () -> {
            if (refuse.get()) {
                throw new IllegalStateException("not stopped");
            }
            closed.incrementAndGet();
        }));
        FakeClient client = new FakeClient();
        ApiEngineConfig settings = config(client).maximumSessions(1).promptProvider(provider, "value").build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext())) {
            AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
            try {
                assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
                assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {}))
                        .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("capacity");
                assertThatThrownBy(() -> ApiSessionStore.open(directory.resolve("profile"), session.reference().providerValue(), true, settings))
                        .isInstanceOf(AgentUnavailableException.class);
            }
            finally {
                refuse.set(false);
                session.close();
            }
            assertThat(closed).hasValue(1);
        }
    }

    @Test
    void blockedContributorRetainsWriterUntilCancellationReallyStopsIt()
            throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        var provider = promptProvider("project", (setting, scope) -> promptContributor(request -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    release.await();
                    done = true;
                }
                catch (InterruptedException ignored) {
                    assertThat(request.cancelled()).isTrue();
                }
            }
            return PromptContribution.empty();
        }, closed::incrementAndGet));
        FakeClient client = new FakeClient();
        ApiEngineConfig settings = config(client).shutdownTimeout(Duration.ofMillis(30)).promptProvider(provider, "value").build();
        ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
        AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
        RunningTurn turn = session.start(command("Hello"));
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            turn.cancel();
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(turn.completion().toCompletableFuture().isDone()).isFalse();
            assertThat(closed).hasValue(0);
            assertThatThrownBy(() -> ApiSessionStore.open(directory.resolve("profile"), session.reference().providerValue(), true, settings))
                    .isInstanceOf(AgentUnavailableException.class);
            assertThat(client.requests).isEmpty();
        }
        finally {
            release.countDown();
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            session.close();
            driver.close();
        }
        assertThat(closed).hasValue(1);
    }

    @Test
    void duplicatePromptProvidersAndNullReturnedSourcesFailWithoutInference()
    {
        FakeClient client = new FakeClient();
        AtomicInteger opened = new AtomicInteger();
        var provider = promptProvider("project", (setting, scope) -> {
            opened.incrementAndGet();
            return null;
        });
        assertThatThrownBy(() -> config(client).promptProvider(provider, "one").promptProvider(provider, "two").build()).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("profile")).doesNotExist();
        assertThat(opened).hasValue(0);
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).promptProvider(provider, "value").build(), connectorContext())) {
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {})).isInstanceOf(AgentUnavailableException.class);
            assertThat(client.requests).isEmpty();
        }
    }

    @Test
    void recursivePromptCleanupCannotReleaseTheSameSessionTwice()
    {
        AtomicReference<AgentSession> holder = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        var provider = promptProvider("project", (setting, scope) -> promptContributor(request -> PromptContribution.empty(), () -> {
            assertThatThrownBy(holder.get()::close).isInstanceOf(AgentUnavailableException.class);
            closed.incrementAndGet();
        }));
        FakeClient client = new FakeClient();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).promptProvider(provider, "value").build(), connectorContext())) {
            AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {});
            holder.set(session);
            session.close();
            session.close();
            assertThat(closed).hasValue(1);
        }
    }

    @Test
    void reentrantCloseDuringPromptOpenCannotReleaseAnInitializingSession()
    {
        AtomicReference<ApiAgentDriver> holder = new AtomicReference<>();
        AtomicReference<AgentSessionRef> reference = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        var provider = promptProvider("project", (setting, scope) -> {
            reference.set(scope.reference());
            assertThatThrownBy(holder.get()::close).isInstanceOf(AgentUnavailableException.class);
            return promptContributor(request -> PromptContribution.empty(), closed::incrementAndGet);
        });
        FakeClient client = new FakeClient();
        ApiEngineConfig settings = config(client).promptProvider(provider, "value").build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext())) {
            holder.set(driver);
            assertThatThrownBy(() -> driver.open(context(Optional.empty(), List.of(), allow()), event -> {})).isInstanceOf(AgentUnavailableException.class);
            assertThat(closed).hasValue(1);
            try (ApiSessionStore ignored = ApiSessionStore.open(directory.resolve("profile"), reference.get().providerValue(), true, settings)) {
                assertThat(ignored.state().phase()).isEqualTo(ApiCheckpoint.Phase.BLOCKED);
            }
        }
    }

    private static PromptContribution contribution(String instructions, String context)
    {
        return new PromptContribution(List.of(new PromptSection("rules", instructions)), List.of(new PromptSection("facts", context)));
    }

    private static PromptContributor promptContributor(java.util.function.Function<PromptContext, PromptContribution> action, Runnable close)
    {
        return new PromptContributor()
        {
            @Override
            public PromptContribution contribute(PromptContext context) { return action.apply(context); }

            @Override
            public void close() { close.run(); }
        };
    }

    private static PromptProvider<String> promptProvider(String id, BiFunction<String, PromptSessionContext, PromptContributor> open)
    {
        return new PromptProvider<>()
        {
            @Override
            public String id() { return id; }

            @Override
            public PromptContributor open(String setting, PromptSessionContext context) { return open.apply(setting, context); }
        };
    }

    @Test
    void internalEngineUsesInjectedRepositoryAndPromptAssemblerWithoutChangingTheDriverContract()
            throws Exception
    {
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> completed(toolResponse("example", "{}")));
        var snapshots = new CopyOnWriteArrayList<ApiCheckpoint>();
        var opened = new AtomicInteger();
        var closed = new AtomicInteger();
        var assembled = new AtomicInteger();
        ApiSessionRepository.Factory repositories = (profile, id, resume, config) -> {
            opened.incrementAndGet();
            assertThat(resume).isFalse();
            return new ApiSessionRepository()
            {
                private ApiCheckpoint state = ApiCheckpoint.initial(id, config.identity, config.lane);

                @Override
                public ApiCheckpoint state() { return state; }

                @Override
                public void save(ApiCheckpoint next)
                {
                    state = next;
                    snapshots.add(next);
                }

                @Override
                public ModelInput.UserMessage stage(String text, List<AgentAttachment> attachments) { return new ModelInput.UserMessage(text, attachments); }

                @Override
                public void close() { closed.incrementAndGet(); }
            };
        };
        ApiPromptAssembler prompts = (context, command, model, effort, tools) -> {
            assembled.incrementAndGet();
            var original = ApiPromptAssembler.DEFAULT.assemble(context, command, model, effort, tools);
            assertThat(original).contains("Host instructions");
            return "Assembled instructions";
        };
        Tool tool = Tool.withSchema("example", "Example", "{}", input -> "Result");
        try (var engine = new ApiEngine(config(client).build(), connectorContext(), repositories, prompts)) {
            AgentSessionContext context = context(Optional.empty(), List.of(tool), allow());
            engine.validate(context);
            assertThat(opened).hasValue(0);
            try (AgentSession session = engine.open(context, event -> {})) {
                assertThat(done(session.start(command("Hello"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(client.requests).hasSize(2).allSatisfy(request -> assertThat(request.instructions()).isEqualTo("Assembled instructions"));
                assertThat(((ModelInput.ToolResults) client.requests.get(1).input()).results())
                        .containsExactly(new ModelInput.ToolResult("call-1", "Result", false));
            }
        }
        assertThat(opened).hasValue(1);
        assertThat(closed).hasValue(1);
        assertThat(assembled).hasValue(1);
        assertThat(snapshots).anySatisfy(state -> assertThat(state.receipts()).anyMatch(ApiToolReceipt::uncertain));
        assertThat(snapshots.getLast().phase()).isEqualTo(ApiCheckpoint.Phase.READY);
        assertThat(directory.resolve("profile")).doesNotExist();
    }

    @Test
    void nullRepositoryDoesNotPublishASessionOrConsumeCapacity()
    {
        FakeClient client = new FakeClient();
        try (var engine = new ApiEngine(config(client).maximumSessions(1).build(), connectorContext(),
                (profile, id, resume, config) -> null, ApiPromptAssembler.DEFAULT)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> engine.open(context(Optional.empty(), List.of(), allow()), event -> {}))
                        .isInstanceOf(NullPointerException.class).hasMessage("repository factory returned null");
            }
        }
        assertThat(client.requests).isEmpty();
        assertThat(directory.resolve("profile")).doesNotExist();
    }

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
    void interfaceBasedLegacyBindingRemainsInsideTheApprovedCallback()
            throws Exception
    {
        AtomicInteger bindings = new AtomicInteger();
        var codec = io.github.shiftinglabs.codej.tool.JsonSchemaParameters.of("{}", input -> {
            bindings.incrementAndGet();
            throw new IllegalArgumentException("Private domain diagnostic");
        });
        Tool tool = Tool.withParameters("example", "Example", codec, input -> "Never");
        for (boolean approved : List.of(false, true)) {
            FakeClient client = new FakeClient();
            client.script.add((request, sink) -> completed(toolResponse("example", "{}")));
            client.script.add((request, sink) -> {
                assertThat(((ModelInput.ToolResults) request.input()).results()).containsExactly(new ModelInput.ToolResult("call-1",
                        approved ? "Host tool failed" : "Tool invocation denied", true));
                return answer(request, sink);
            });
            AgentApprovalHandler approvals = (request, wait) -> {
                assertThat(bindings).hasValue(0);
                return CompletableFuture.completedFuture(approved ? ProviderApprovalDecision.allow(Optional.empty()) : ProviderApprovalDecision.deny());
            };
            try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.empty(), List.of(tool), approvals), event -> {})) {
                assertThat(done(session.start(command("Example"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(bindings).hasValue(approved ? 1 : 0);
            }
        }
    }

    @Test
    void migrationKeepsConfiguredLegacyLimitsAndBorrowedHostExecution()
            throws Exception
    {
        String large = "x".repeat(70000);
        String schema = "{\"description\":\"" + large + "\"}";
        String arguments = "{\"value\":\"" + large + "\"}";
        AtomicInteger submitted = new AtomicInteger();
        FakeClient client = new FakeClient();
        Tool tool = Tool.withSchema("large", large, schema, input -> {
            assertThat(input).isEqualTo(arguments);
            return large;
        });
        client.script.add((request, sink) -> {
            assertThat(request.tools()).containsExactly(new ModelRequest.ToolDefinition("large", large, schema));
            return completed(toolResponse("large", arguments));
        });
        client.script.add((request, sink) -> {
            assertThat(((ModelInput.ToolResults) request.input()).results()).containsExactly(new ModelInput.ToolResult("call-1", large, false));
            return answer(request, sink);
        });
        ApiEngineConfig settings = config(client).maximumToolOutputChars(80000).toolExecutor(task -> {
            submitted.incrementAndGet();
            task.run();
        }).build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
            assertThat(done(session.start(command("Large"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(submitted).hasValue(1);
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
    void neutralHistoryTracksToolContinuationAndSurvivesRestartWithoutPrivateReplayContent()
            throws Exception
    {
        FakeClient first = new FakeClient();
        first.script.add((request, sink) -> {
            assertThat(request.history().orElseThrow().complete()).isTrue();
            assertThat(request.history().orElseThrow().exchanges()).isEmpty();
            return completed(toolResponse("lookup", "{}"));
        });
        first.script.add((request, sink) -> {
            var history = request.history().orElseThrow();
            assertThat(history.exchanges()).hasSize(1);
            var exchange = history.exchanges().getFirst();
            assertThat(exchange.input()).isEqualTo(new ModelInput.UserMessage("Look up", List.of()));
            assertThat(exchange.toolCalls()).containsExactly(new ModelResponse.ToolCall("call-1", "lookup", "{}"));
            assertThat(exchange.providerId()).isEqualTo("fake-api");
            assertThat(exchange.model()).isEqualTo(request.model());
            assertThat(exchange.text()).isEmpty();
            assertThat(request.input()).isEqualTo(new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call-1", "Found", false))));
            return answer(request, sink);
        });
        Tool tool = Tool.withSchema("lookup", "Lookup", "{}", input -> "Found");
        AgentSessionRef reference;
        try (ApiAgentDriver driver = driver(first); AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
            assertThat(done(session.start(command("Look up"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            reference = session.reference();
        }
        FakeClient second = new FakeClient();
        second.script.add((request, sink) -> {
            var history = request.history().orElseThrow();
            assertThat(history.complete()).isTrue();
            assertThat(history.exchanges()).hasSize(2);
            assertThat(history.exchanges().getLast().input()).isInstanceOf(ModelInput.ToolResults.class);
            assertThat(history.exchanges().getLast().text()).isEqualTo("answer");
            assertThat(history.toString()).doesNotContain("after-call", "Found");
            assertThat(request.input()).isEqualTo(new ModelInput.UserMessage("Continue", List.of()));
            return answer(request, sink);
        });
        try (ApiAgentDriver driver = driver(second); AgentSession session = driver.open(context(Optional.of(reference), List.of(tool), allow()), event -> {})) {
            assertThat(done(session.start(command("Continue"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
        try (ApiSessionStore store = ApiSessionStore.open(directory.resolve("profile"), reference.providerValue(), true, config(new FakeClient()).build())) {
            assertThat(store.state().history().exchanges()).hasSize(3);
            assertThat(store.state().history().complete()).isTrue();
        }
    }

    @Test
    void legacyRestartPreservesPrivateContinuationAndExtendsOnlyTheKnownHistorySuffix()
            throws Exception
    {
        AgentSessionRef reference;
        try (ApiAgentDriver driver = driver(new FakeClient()); AgentSession session = driver.open(context(Optional.empty(), List.of(), allow()), event -> {})) {
            assertThat(done(session.start(command("Legacy input"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            reference = session.reference();
        }
        var old = (com.fasterxml.jackson.databind.node.ObjectNode) ApiCheckpointCodec.JSON.readTree(Files.readAllBytes(checkpoint(reference)));
        String nativeReplay = old.withObject("replay").path("payload").textValue();
        old.put("version", 1);
        old.remove("history");
        byte[] legacy = ApiCheckpointCodec.JSON.writeValueAsBytes(old);
        Files.write(checkpoint(reference), legacy);
        FakeClient client = new FakeClient();
        client.script.add((request, sink) -> {
            assertThat(request.history().orElseThrow().complete()).isFalse();
            assertThat(request.history().orElseThrow().exchanges()).isEmpty();
            assertThat(request.replayState().orElseThrow().payload()).isEqualTo(nativeReplay);
            return answer(request, sink);
        });
        try (ApiAgentDriver driver = driver(client); AgentSession session = driver.open(context(Optional.of(reference), List.of(), allow()), event -> {})) {
            assertThat(Files.readAllBytes(checkpoint(reference))).isEqualTo(legacy);
            assertThat(session.reference().contractVersion()).isEqualTo(1);
            assertThat(done(session.start(command("Known suffix"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
        try (ApiSessionStore store = ApiSessionStore.open(directory.resolve("profile"), reference.providerValue(), true, config(new FakeClient()).build())) {
            assertThat(store.state().version()).isEqualTo(ApiCheckpoint.CURRENT_VERSION);
            assertThat(store.state().history().complete()).isFalse();
            assertThat(store.state().history().exchanges()).hasSize(1);
            assertThat(store.state().history().exchanges().getFirst().input()).isEqualTo(new ModelInput.UserMessage("Known suffix", List.of()));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledToolKeepsItsObservedSuccessAndRecoveryDoesNotExecuteItAgain(boolean borrowed)
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
        try (var hostExecutor = Executors.newSingleThreadExecutor();
                ApiAgentDriver driver = new ApiAgentDriver(borrowed ? config(first).toolExecutor(hostExecutor).build() : config(first).build(), connectorContext());
                AgentSession session = driver.open(context(Optional.empty(), List.of(tool), allow()), event -> {})) {
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
        try (ApiSessionStore store = ApiSessionStore.open(directory.resolve("profile"), reference.providerValue(), true, config(new FakeClient()).build())) {
            ApiCheckpoint state = store.state();
            ApiRequestSetup setup = state.setup().orElseThrow();
            assertThat(setup.toolContracts()).isPresent();
            var legacy = new ApiRequestSetup(setup.model(), setup.instructions(), setup.effort(), setup.tier(), setup.tools(), Optional.empty());
            store.save(new ApiCheckpoint(state.version(), state.id(), state.identity(), state.lane(), state.phase(), state.journal(), state.receipts(),
                    state.replay(), Optional.of(legacy), state.input(), state.history()));
        }
        FakeClient second = new FakeClient();
        second.script.add((request, sink) -> {
            assertThat(((ModelInput.ToolResults) request.input()).results()).containsExactly(new ModelInput.ToolResult("call-1", "Report saved", false));
            assertThat(request.history().orElseThrow().exchanges()).hasSize(1);
            assertThat(request.history().orElseThrow().exchanges().getFirst().input()).isNotEqualTo(new ModelInput.UserMessage("Continue", List.of()));
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
            ApiCheckpoint state = store.state();
            var receipt = new ApiToolReceipt("call-1", "write_report", "{}", Optional.empty(), true, Optional.empty());
            store.save(new ApiCheckpoint(state.version(), state.id(), state.identity(), state.lane(), ApiCheckpoint.Phase.TOOLS,
                    state.journal(), List.of(receipt), state.replay(), state.setup(), state.input(), new io.github.shiftinglabs.codej.cli.spi.model.ModelHistory(false, List.of())));
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
            var state = ApiCheckpointCodec.JSON.readTree(Files.readString(checkpoint(session.reference())));
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
            var state = ApiCheckpointCodec.JSON.readTree(Files.readString(checkpoint(session.reference())));
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
                assertThat(ApiCheckpointCodec.JSON.readTree(Files.readString(checkpoint(session.reference()))).path("replay").path("payload").asText())
                        .isEqualTo("observed-terminal");
            }
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void builtinsRoundTripThroughTypedToolsWithoutTheHostExecutor()
            throws Exception
    {
        FakeClient client = new FakeClient();
        FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        Files.writeString(directory.resolve("source.txt"), "original");
        client.script.add((request, sink) -> completed(toolResponse("read_file", "{\"path\":\"source.txt\"}")));
        ApiEngineConfig settings = config(client).codingTools(CodingTools.defaults()).toolExecutor(task -> {
            throw new AssertionError("Built-in execution belongs on the turn worker");
        }).build();
        try (ApiAgentDriver driver = new ApiAgentDriver(settings, new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT));
                AgentSession session = driver.open(codingContext(SessionAccess.FULL, allow()), event -> {})) {
            supervisor.beforeStart = () -> {
                try {
                    assertThat(Files.readString(checkpoint(session.reference()))).contains("coding-launch", "launch-2");
                }
                catch (java.io.IOException failure) {
                    throw new AssertionError(failure);
                }
            };
            assertThat(done(session.start(command("read"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(client.requests.getFirst().tools()).extracting(ModelRequest.ToolDefinition::name)
                    .contains("read_file", "list_files", "search_files", "apply_patch", "shell_command");
            var results = (ModelInput.ToolResults) client.requests.getLast().input();
            assertThat(results.results().getFirst().error()).isFalse();
            assertThat(results.results().getFirst().text()).contains("original", "sha256");
            client.script.add((request, sink) -> completed(toolResponse("shell_command",
                    "{\"argv\":[\"/usr/bin/true\"],\"directory\":\".\",\"timeoutSeconds\":1}")));
            assertThat(done(session.start(command("command"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(supervisor.acknowledged).containsExactly("launch-1", "launch-2");
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void builtinsAlwaysRequireApprovalAndApprovalCannotWidenReadonlyAccess()
            throws Exception
    {
        FakeClient client = new FakeClient();
        FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        AtomicInteger approvals = new AtomicInteger();
        AgentApprovalHandler deny = (request, wait) -> {
            approvals.incrementAndGet();
            return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        };
        client.script.add((request, sink) -> completed(toolResponse("apply_patch",
                "{\"path\":\"created\",\"action\":\"CREATE\",\"edits\":[{\"before\":\"\",\"after\":\"content\"}]}")));
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).codingTools(CodingTools.defaults()).approveTools(false).build(),
                new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT))) {
            try (AgentSession session = driver.open(codingContext(SessionAccess.FULL, deny), event -> {})) {
                assertThat(done(session.start(command("create"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(approvals).hasValue(1);
                assertThat(directory.resolve("created")).doesNotExist();
                assertThat(((ModelInput.ToolResults) client.requests.getLast().input()).results().getFirst().error()).isTrue();
            }
            client.script.add((request, sink) -> completed(toolResponse("apply_patch",
                    "{\"path\":\"created\",\"action\":\"CREATE\",\"edits\":[{\"before\":\"\",\"after\":\"content\"}]}")));
            try (AgentSession session = driver.open(codingContext(SessionAccess.READ_ONLY, allow()), event -> {})) {
                assertThat(done(session.start(command("create"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(client.requests.getLast().tools()).extracting(ModelRequest.ToolDefinition::name).doesNotContain("apply_patch");
                assertThat(directory.resolve("created")).doesNotExist();
            }
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void uncertainBuiltInProcessKeepsTheReceiptAndBothWriterLocks()
            throws Exception
    {
        FakeClient client = new FakeClient();
        FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        ApiAgentDriver driver = new ApiAgentDriver(config(client).codingTools(CodingTools.defaults()).build(),
                new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT));
        AgentSession session = driver.open(codingContext(SessionAccess.FULL, allow()), event -> {});
        AgentSessionRef reference = session.reference();
        try {
            supervisor.verified = false;
            client.script.add((request, sink) -> completed(toolResponse("shell_command",
                    "{\"argv\":[\"/usr/bin/true\"],\"directory\":\".\",\"timeoutSeconds\":1}")));
            assertThat(done(session.start(command("command"))).state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(session.resumable()).isFalse();
            assertThatThrownBy(() -> session.start(command("again"))).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(session::close).isInstanceOf(AgentUnavailableException.class);
            assertThat(supervisor.acknowledged).containsExactly("launch-1");
            assertThatThrownBy(() -> driver.open(codingContext(SessionAccess.FULL, allow()), event -> {})).isInstanceOf(AgentUnavailableException.class);
        }
        finally {
            supervisor.verified = true;
            session.close();
            driver.close();
        }
        AgentSessionContext resume = codingContext(SessionAccess.FULL, allow());
        resume = new AgentSessionContext(resume.taskId(), resume.worktree(), resume.runtimeDirectory(), resume.providerDirectory(), resume.scope(),
                Optional.of(reference), resume.approvals(), resume.model(), resume.effort(), resume.managedInstructions(), List.of(), false);
        try (ApiAgentDriver restarted = new ApiAgentDriver(config(new FakeClient()).codingTools(CodingTools.defaults()).build(),
                new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT));
                AgentSession recovered = restarted.open(resume, event -> {})) {
            assertThat(recovered.resumable()).isFalse();
            assertThatThrownBy(() -> recovered.start(command("do not replay"))).isInstanceOf(AgentUnavailableException.class);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void cancellingBuiltInCommandVerifiesStopBeforeSavingItsOutcome()
            throws Exception
    {
        FakeClient client = new FakeClient();
        FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(client).codingTools(CodingTools.defaults()).build(),
                new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT));
                AgentSession session = driver.open(codingContext(SessionAccess.FULL, allow()), event -> {})) {
            supervisor.pendingExit = new CompletableFuture<>();
            client.script.add((request, sink) -> completed(toolResponse("shell_command",
                    "{\"argv\":[\"/bin/sleep\",\"5\"],\"directory\":\".\",\"timeoutSeconds\":10}")));
            RunningTurn turn = session.start(command("command"));
            assertThat(supervisor.waiting.await(2, TimeUnit.SECONDS)).isTrue();
            turn.cancel();
            assertThat(done(turn).state()).isEqualTo(AgentTurnResult.State.CANCELLED);
            assertThat(supervisor.acknowledged).containsExactly("launch-1", "launch-2");
            assertThat(session.resumable()).isTrue();
            assertThat(done(session.start(command("continue"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(supervisor.specs).hasSize(2);
        }
    }

    @Test
    void codingValidationIsSideEffectFreeAndRejectsUnenforceableConstraints()
    {
        FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        try (ApiAgentDriver driver = new ApiAgentDriver(config(new FakeClient()).codingTools(CodingTools.defaults()).build(),
                new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT))) {
            AgentSessionContext full = codingContext(SessionAccess.FULL, allow());
            driver.validate(full);
            driver.validate(full.withTaskScope(full.scope().withApproveEveryEdit(true).withApproveEveryCommand(true), false));
            assertThatThrownBy(() -> driver.validate(full.withTaskScope(full.scope().withNetwork(true), false))).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> driver.validate(full.withTaskScope(full.scope().withWritableRoots(List.of(directory)), false)))
                    .isInstanceOf(AgentUnavailableException.class);
            assertThat(supervisor.specs).isEmpty();
            assertThat(directory.resolve("profile")).doesNotExist();
        }
    }

    private AgentSessionContext codingContext(SessionAccess access, AgentApprovalHandler approvals)
    {
        return new AgentSessionContext("task", directory, directory.resolve("runtime"), directory.resolve("runtime/provider"), AgentPermissionPolicy.AUTO,
                Optional.empty(), approvals, Optional.empty(), Optional.empty(), access, "Host instructions", List.of());
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

    private ApiCheckpoint readCheckpoint(AgentSessionRef reference)
    {
        try {
            return ApiCheckpointCodec.decode(Files.readAllBytes(checkpoint(reference)));
        }
        catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
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
        boolean summarySupport;
        final List<ModelCompactionRequest> compactions = new CopyOnWriteArrayList<>();
        java.util.function.Function<ModelCompactionRequest, Optional<ModelReplayState>> compactor;

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
        public boolean supportsContextCompaction() { return summarySupport; }

        @Override
        public Optional<ModelReplayState> compact(ModelCompactionRequest request)
        {
            compactions.add(request);
            return compactor == null ? ModelClient.super.compact(request) : compactor.apply(request);
        }

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

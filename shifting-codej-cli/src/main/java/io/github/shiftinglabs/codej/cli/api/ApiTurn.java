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

import io.github.shiftinglabs.codej.cli.api.tools.BuiltinTools;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnFailureKind;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderTurnRef;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactionContext;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPlan;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineObservation;
import io.github.shiftinglabs.codej.cli.spi.extension.ToolPolicyContext;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompaction;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionMemory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextAssessment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContext;
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetention;
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetentionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static io.github.shiftinglabs.codej.cli.api.ApiEngine.CALLBACK;
import static java.util.Objects.requireNonNull;

final class ApiTurn
        implements RunningTurn
{
    private final ApiSession session;
    private final ApiEngineConfig config;
    private final AgentTurnCommand command;
    private final ModelInput.UserMessage input;
    private final String model;
    private final Optional<String> effort;
    private final Instant started = Instant.now();
    private final long startedNanos = System.nanoTime();
    final CompletableFuture<AgentTurnResult> result = new CompletableFuture<>();
    private final AtomicReference<String> stopping = new AtomicReference<>();
    private volatile ModelCall call;
    volatile Thread runner;
    private volatile boolean interruptible;
    private volatile CompletableFuture<?> approval;
    ScheduledFuture<?> timeout;
    private int requests;
    private final ToolDispatcher.Turn toolTurn;
    private long tokens;
    private int textChars;

    ApiTurn(ApiSession session, AgentTurnCommand command, ModelInput.UserMessage input, String model, Optional<String> effort)
    {
        this.session = session;
        config = session.engine.config;
        this.command = command;
        this.input = input;
        this.model = model;
        this.effort = effort;
        toolTurn = session.dispatcher.turn(config.maximumToolCalls);
    }

    @Override
    public ProviderTurnRef reference() { return new ProviderTurnRef(command.turnId()); }

    @Override
    public CompletionStage<AgentTurnResult> completion() { return result.minimalCompletionStage(); }

    @Override
    public void cancel() { stop("API turn cancelled"); }

    void stop(String reason)
    {
        if (result.isDone() || !stopping.compareAndSet(null, reason)) {
            return;
        }
        toolTurn.cancel();
        ModelCall active = call;
        if (active != null) {
            try {
                active.cancel();
            }
            catch (RuntimeException failure) {
                // Keep ownership until completion proves the stream stopped; close will report a timeout otherwise.
            }
        }
        CompletableFuture<?> pending = approval;
        if (pending != null) {
            pending.cancel(false);
        }
        Thread thread = runner;
        if (thread != null && interruptible) {
            thread.interrupt();
        }
    }

    private void checkStop()
    {
        if (stopping.get() != null) {
            throw new Stopped(stopping.get());
        }
    }

    private ProviderAgentEventSource source(String item)
    {
        return new ProviderAgentEventSource(Optional.of(session.reference.providerValue()), Optional.empty(), Optional.empty(),
                Optional.of(command.turnId()), Optional.of(item));
    }

    private synchronized void emit(ProviderAgentEvent event)
    {
        ApiSession previous = CALLBACK.get();
        CALLBACK.set(session);
        try {
            session.events.emit(event);
        }
        finally {
            if (previous == null) {
                CALLBACK.remove();
            }
            else {
                CALLBACK.set(previous);
            }
        }
    }

    void run()
    {
        runner = Thread.currentThread();
        AgentTurnResult terminal;
        try {
            checkStop();
            observe(() -> new EngineObservation.TurnStarted(observedTurnId()));
            checkStop();
            emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.TURN_STARTED, "API turn started", source("turn")));
            validateRetainedHistory();
            ApiCheckpoint saved = session.store.state();
            if (saved.phase() == ApiCheckpoint.Phase.TOOLS) {
                // Reconcile an interrupted batch before adding fresh user input to the provider's transcript.
                runLoop(results(true), saved.setup().orElseThrow());
            }
            compact();
            retainAudit(true);
            ApiPromptSnapshot prompt = prompt();
            String answer = runLoop(prompt.input(), requestSetup(prompt.instructions()));
            retainAudit(false);
            checkStop();
            emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED, "API turn completed", source("turn")));
            terminal = terminal(AgentTurnResult.State.COMPLETED, Optional.of(answer).filter(value -> !value.isBlank()), Optional.empty(), Optional.empty(), false);
        }
        catch (Stopped failure) {
            terminal = terminal(stopping.get() != null && stopping.get().equals("API turn cancelled")
                    ? AgentTurnResult.State.CANCELLED : AgentTurnResult.State.INTERRUPTED,
                    Optional.empty(), Optional.of(failure.getMessage()), Optional.empty(), false);
        }
        catch (ModelClientException failure) {
            Optional<AgentTurnFailureKind> kind = failure.kind() == ModelClientException.Kind.AUTHENTICATION_REQUIRED
                    ? Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED) : Optional.empty();
            boolean transientFailure = failure.kind() == ModelClientException.Kind.RATE_LIMITED
                    || failure.kind() == ModelClientException.Kind.TEMPORARILY_UNAVAILABLE;
            terminal = terminal(AgentTurnResult.State.FAILED, Optional.empty(), Optional.of(config.redactor().redact(failure.getMessage())), kind, transientFailure);
        }
        catch (Throwable failure) {
            terminal = terminal(AgentTurnResult.State.FAILED, Optional.empty(), Optional.of("API turn failed; inspect its checkpoint before recovery"),
                    Optional.empty(), false);
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
        AgentTurnResult executionOutcome = terminal;
        try {
            observe(() -> new EngineObservation.TurnFinished(observedTurnId(), executionOutcome.state(), executionOutcome.elapsed()));
            session.finished();
        }
        catch (RuntimeException failure) {
            terminal = terminal(AgentTurnResult.State.FAILED, Optional.empty(), Optional.of("API session writer could not be released"), Optional.empty(), false);
        }
        finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
            Thread.interrupted();
        }
        result.complete(terminal);
    }

    private AgentTurnResult terminal(AgentTurnResult.State state, Optional<String> answer, Optional<String> reason,
            Optional<AgentTurnFailureKind> kind, boolean transientFailure)
    {
        return new AgentTurnResult(state, answer, reason, Duration.ofNanos(System.nanoTime() - startedNanos), true, kind, transientFailure);
    }

    private String observedTurnId() { return config.redactor().redact(command.turnId()); }

    private void observe(Supplier<EngineObservation> observation)
    {
        if (session.extensions.isEmpty()) {
            return;
        }
        try {
            interruptible = true;
            if (stopping.get() != null) {
                Thread.currentThread().interrupt();
            }
            session.extensions.observe(observation.get());
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
    }

    private ApiRequestSetup requestSetup(String instructions)
    {
        return new ApiRequestSetup(model, instructions, effort, command.serviceTier(), session.tools.definitions(), Optional.of(session.tools.contracts()));
    }

    private void compact() { compact(Optional.empty()); }

    private void compact(Optional<ModelContextAssessment> assessment)
    {
        if ((config.contextCompactor.isEmpty() && config.contextSummarization.isEmpty()) || session.store.state().replay().isEmpty()) {
            return;
        }
        ApiCheckpoint before = session.store.state();
        if (before.phase() != ApiCheckpoint.Phase.READY) {
            throw new IllegalArgumentException("Context compaction requires complete settled history");
        }
        ContextCompactionContext context = new ContextCompactionContext()
        {
            @Override
            public String turnId() { return command.turnId(); }

            @Override
            public String model() { return model; }

            @Override
            public ModelHistory history() { return before.history(); }

            @Override
            public int replayCharacters() { return before.replay().orElseThrow().payload().length(); }

            @Override
            public Optional<ModelContextAssessment> contextAssessment() { return assessment; }

            @Override
            public Optional<Instant> deadline()
            {
                return command.maximumRuntime().isZero() ? Optional.empty() : Optional.of(started.plus(command.maximumRuntime()));
            }

            @Override
            public boolean cancelled() { return stopping.get() != null; }
        };
        try {
            interruptible = true;
            checkStop();
            if (config.contextSummarization.isPresent()) {
                var plan = requireNonNull(config.contextSummarization.orElseThrow().plan(context), "summary policy returned null");
                checkStop();
                plan.ifPresent(value -> summarize(before, value, context.deadline()));
            }
            else {
                var offered = requireNonNull(config.contextCompactor.orElseThrow().propose(context), "compactor returned null");
                checkStop();
                offered.ifPresent(proposal -> rewrite(before, proposal, context.deadline()));
            }
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
    }

    private boolean rewrite(ApiCheckpoint before, ModelCompaction proposal, Optional<Instant> deadline)
    {
        if (proposal.summary().length() > config.maximumTextChars) {
            throw new IllegalArgumentException("Compaction summary exceeds its text budget");
        }
        ModelReplayState original = before.replay().orElseThrow();
        ModelCompactionRequest request = new ModelCompactionRequest(model, before.history(), original, proposal, deadline);
        try {
            checkStop();
            interruptible = true;
            ModelReplayState replacement = requireNonNull(session.engine.client.compact(request), "adapter returned null").orElseThrow(() ->
                    new IllegalArgumentException("Model adapter does not support context compaction"));
            checkStop();
            if (replacement.providerId().equals(original.providerId()) && replacement.formatVersion() == original.formatVersion()
                    && replacement.payload().equals(original.payload())) {
                return false;
            }
            if (!replacement.providerId().equals(original.providerId()) || replacement.payload().length() >= original.payload().length()
                    || replacement.payload().length() > config.maximumCheckpointBytes) {
                throw new IllegalArgumentException("Invalid or non-reducing compacted replay");
            }
            save(before.withReplay(replacement));
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
        observe(() -> new EngineObservation.ContextCompacted(observedTurnId(), before.history().exchanges().size() - proposal.retainExchanges(), proposal.retainExchanges()));
        checkStop();
        return true;
    }

    private void summarize(ApiCheckpoint before, ContextSummarizationPlan plan, Optional<Instant> deadline)
    {
        if (!session.engine.client.supportsContextCompaction()) {
            throw new IllegalArgumentException("Model adapter does not support engine context summarization");
        }
        ModelReplayState replay = before.replay().orElseThrow();
        ModelHistory history = before.history();
        boolean anchored = history.summarizedThroughRequestId().isPresent();
        int target = Math.max(0, history.exchanges().size() - plan.retainExchanges());
        if (!anchored || target > 0) {
            ModelCompactionRequest.validateBoundary(history, replay, plan.retainExchanges());
        }
        Optional<ModelCompactionMemory> memory = memory(replay);
        int from = ApiAuditRetention.cursor(history, memory);
        if (from > target) {
            throw new IllegalArgumentException("Summary cannot restore removed context");
        }
        String summary = memory.map(ModelCompactionMemory::summary).orElse("");
        int limit = Math.min(config.maximumSummaryInputCharacters, config.maximumTextChars);
        while (from < target) {
            checkStop();
            GeneratedSummary generated = summaryChunk(history, from, target, summary, limit, plan.maximumOutputTokens(), deadline);
            summary = generated.text();
            var proposal = new ModelCompaction(summary, history.exchanges().size() - generated.throughExchanges());
            if (!rewrite(session.store.state(), proposal, deadline)) {
                throw new IllegalArgumentException("Incremental compaction made no progress");
            }
            from = generated.throughExchanges();
            retainAudit(true);
        }
    }

    private Optional<ModelCompactionMemory> memory(ModelReplayState replay)
    {
        try {
            interruptible = true;
            checkStop();
            var value = requireNonNull(session.engine.client.compactionMemory(replay), "adapter returned null memory");
            checkStop();
            return value;
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
    }

    private void validateRetainedHistory()
    {
        ApiCheckpoint state = session.store.state();
        if (state.history().summarizedThroughRequestId().isPresent()) {
            ApiAuditRetention.cursor(state.history(), memory(state.replay().orElseThrow()));
        }
    }

    private void retainAudit(boolean freshPending)
    {
        if (config.auditRetention.isEmpty()) {
            return;
        }
        ApiCheckpoint before = session.store.state();
        if (before.phase() != ApiCheckpoint.Phase.READY || !before.recoverable() || !before.receipts().isEmpty()) {
            return;
        }
        var memory = before.replay().isPresent() ? memory(before.replay().orElseThrow()) : Optional.<ModelCompactionMemory>empty();
        AuditRetentionContext context = new AuditRetentionContext()
        {
            @Override
            public String turnId() { return command.turnId(); }

            @Override
            public ModelHistory history() { return before.history(); }

            @Override
            public Optional<String> compactedThroughRequestId() { return memory.map(ModelCompactionMemory::throughRequestId); }

            @Override
            public Optional<Instant> deadline() { return command.maximumRuntime().isZero() ? Optional.empty() : Optional.of(started.plus(command.maximumRuntime())); }

            @Override
            public boolean cancelled() { return stopping.get() != null; }
        };
        AuditRetention plan;
        try {
            interruptible = true;
            checkStop();
            var offered = requireNonNull(config.auditRetention.orElseThrow().retain(context), "retention policy returned null");
            checkStop();
            if (offered.isEmpty()) {
                return;
            }
            plan = offered.orElseThrow();
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
        ApiCheckpoint next = ApiAuditRetention.apply(before, memory, plan, freshPending ? Optional.of(input) : Optional.empty());
        checkStop();
        if (next != before) {
            save(next);
        }
        int removedAttachments = 0;
        if (plan.maximumAttachmentDeletions() > 0 && (next.history().complete() || next.history().summarizedThroughRequestId().isPresent())) {
            removedAttachments = session.store.pruneAttachments(plan.maximumAttachmentDeletions(), freshPending ? input.attachments() : List.of(), this::checkStop);
        }
        int deleted = removedAttachments;
        if (next != before || deleted > 0) {
            observe(() -> new EngineObservation.AuditPruned(observedTurnId(), before.history().exchanges().size() - next.history().exchanges().size(),
                    before.journal().size() - next.journal().size(), deleted));
        }
        checkStop();
    }

    private record GeneratedSummary(String text, int throughExchanges)
    {
        @Override
        public String toString() { return "GeneratedSummary[redacted]"; }
    }

    private GeneratedSummary summaryChunk(ModelHistory history, int from, int target, String memory, int limit,
            int maximumOutputTokens, Optional<Instant> deadline)
    {
        String id = UUID.randomUUID().toString();
        ApiSummaryPrompt.Chunk chunk;
        ModelRequest request;
        int end = target;
        while (true) {
            checkStop();
            chunk = ApiSummaryPrompt.chunk(history, from, end, memory, limit);
            String transcript = config.redactor().redact(chunk.text());
            if (transcript.length() > limit) {
                throw new IllegalArgumentException("Redacted summary input exceeds its budget");
            }
            var builder = ModelRequest.builder(id, model, new ModelInput.UserMessage(transcript, List.of()), availableOutput(maximumOutputTokens, true))
                    .instructions(ApiSummaryPrompt.INSTRUCTIONS).history(new ModelHistory(true, List.of()));
            deadline.ifPresent(builder::deadline);
            effort.ifPresent(builder::effort);
            command.serviceTier().ifPresent(builder::serviceTier);
            BudgetedRequest checked = assess(builder.build());
            if (checked.fits()) {
                request = checked.request();
                break;
            }
            if (!canReduceInput(checked)) {
                throw contextFailure("Output reservation and safety margin leave no provider input capacity");
            }
            end = ApiSummaryPrompt.smallerBoundary(history, from, chunk.throughExchanges());
            if (end >= chunk.throughExchanges()) {
                throw contextFailure("The next complete summary group exceeds its provider context budget");
            }
        }
        reserveRequest(request.maximumOutputTokens(), true);
        int maximum = Math.min(ModelCompaction.MAX_SUMMARY_CHARACTERS, config.maximumTextChars - textChars);
        if (maximum <= 0) {
            throw new Stopped("API turn text budget exhausted");
        }
        int previousText = textChars;
        ModelResponse response = generate(request, new StreamText(id, false, maximum));
        recordUsage(id, response);
        observe(() -> new EngineObservation.ContextSummaryCompleted(observedTurnId(), id, response.stopReason(), response.usage()));
        checkStop();
        if (tokens >= config.maximumTokens) {
            throw new Stopped("API turn token budget exhausted");
        }
        if (response.stopReason() != ModelResponse.StopReason.COMPLETED || !response.toolCalls().isEmpty()
                || response.text().isBlank() || response.text().length() > maximum) {
            throw new IllegalArgumentException("Context summary was not a bounded complete text response");
        }
        textChars = Math.max(textChars, previousText + response.text().length());
        if (!config.redactor().redact(response.text()).equals(response.text())) {
            throw new IllegalArgumentException("Context summary contains a known credential");
        }
        return new GeneratedSummary(response.text(), chunk.throughExchanges());
    }

    private int availableOutput(int requestedOutputTokens, boolean continuation)
    {
        checkStop();
        int reserve = continuation ? 1 : 0;
        if (config.maximumRequests - requests <= reserve || config.maximumTokens - tokens <= reserve) {
            throw new Stopped("API turn request or token budget exhausted");
        }
        return (int) Math.min(requestedOutputTokens, Math.min(config.maximumOutputTokens, config.maximumTokens - tokens - reserve));
    }

    private int reserveRequest(int requestedOutputTokens, boolean continuation)
    {
        int output = availableOutput(requestedOutputTokens, continuation);
        requests++;
        return output;
    }

    private record BudgetedRequest(ModelRequest request, Optional<ModelContextAssessment> assessment, boolean fits)
    {
        @Override
        public String toString() { return "BudgetedRequest[redacted]"; }
    }

    private static ModelClientException contextFailure(String message)
    {
        return new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, message, false);
    }

    private BudgetedRequest assess(ModelRequest request)
    {
        if (config.contextBudget.isEmpty()) {
            return new BudgetedRequest(request, Optional.empty(), true);
        }
        Optional<ModelContextAssessment> assessment;
        try {
            interruptible = true;
            checkStop();
            assessment = requireNonNull(session.engine.client.contextBudget(request), "adapter returned null context assessment");
            checkStop();
        }
        catch (CancellationException cancelled) {
            throw new Stopped(stopping.get() == null ? "Context assessment cancelled" : stopping.get());
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
        var policy = config.contextBudget.orElseThrow();
        int output = assessment.map(value -> Math.min(request.maximumOutputTokens(), value.maximumOutputTokens())).orElse(request.maximumOutputTokens());
        boolean fits = assessment.map(value -> value.fits(output, policy.safetyMarginTokens())).orElse(!policy.requireKnownSize());
        var decision = assessment.isPresent() ? fits ? EngineObservation.ContextDecision.FIT : EngineObservation.ContextDecision.OVER_LIMIT
                : fits ? EngineObservation.ContextDecision.UNKNOWN_ALLOWED : EngineObservation.ContextDecision.UNKNOWN_REJECTED;
        observe(() -> new EngineObservation.ContextAssessed(observedTurnId(), request.requestId(), assessment, output, policy.safetyMarginTokens(), decision));
        checkStop();
        if (assessment.isEmpty() && !fits) {
            throw contextFailure("Provider context sizing is required but unavailable");
        }
        return new BudgetedRequest(output == request.maximumOutputTokens() ? request : copyRequest(request, output), assessment, fits);
    }

    private ModelRequest copyRequest(ModelRequest original, int output)
    {
        var builder = ModelRequest.builder(original.requestId(), original.model(), original.input(), output)
                .instructions(original.instructions()).tools(original.tools());
        original.history().ifPresent(builder::history);
        original.replayState().ifPresent(builder::replayState);
        original.effort().ifPresent(builder::effort);
        original.serviceTier().ifPresent(builder::serviceTier);
        original.deadline().ifPresent(builder::deadline);
        return builder.build();
    }

    private ModelRequest request(String id, ApiRequestSetup setup, ModelInput nextInput)
    {
        var before = session.store.state();
        var builder = ModelRequest.builder(id, setup.model(), nextInput, availableOutput(config.maximumOutputTokens, false))
                .instructions(setup.instructions()).tools(setup.tools()).history(before.history());
        before.replay().ifPresent(builder::replayState);
        setup.effort().ifPresent(builder::effort);
        setup.tier().ifPresent(builder::serviceTier);
        if (!command.maximumRuntime().isZero()) {
            builder.deadline(started.plus(command.maximumRuntime()));
        }
        return builder.build();
    }

    private boolean canReduceInput(BudgetedRequest checked)
    {
        if (checked.assessment().isEmpty()) {
            return false;
        }
        long window = checked.assessment().orElseThrow().contextWindowTokens();
        long margin = config.contextBudget.orElseThrow().safetyMarginTokens();
        return margin < window && checked.request().maximumOutputTokens() < window - margin;
    }

    private ModelRequest prepareRequest(String id, ApiRequestSetup setup, ModelInput nextInput)
    {
        BudgetedRequest checked = assess(request(id, setup, nextInput));
        ApiCheckpoint before = session.store.state();
        if (!checked.fits() && canReduceInput(checked) && nextInput instanceof ModelInput.UserMessage && before.phase() == ApiCheckpoint.Phase.READY
                && before.replay().isPresent() && (config.contextCompactor.isPresent() || config.contextSummarization.isPresent())) {
            compact(checked.assessment());
            retainAudit(true);
            checked = assess(request(id, setup, nextInput));
        }
        if (!checked.fits()) {
            throw contextFailure("Provider context budget exceeded; no model request was submitted");
        }
        return checked.request();
    }

    private ModelResponse generate(ModelRequest request, StreamText stream)
    {
        try {
            checkStop();
            interruptible = true;
            call = requireNonNull(session.engine.client.generate(request, stream::text), "adapter returned null call");
            interruptible = false;
            Thread.interrupted();
            if (stopping.get() != null) {
                call.cancel();
            }
            ModelResponse response = requireNonNull(awaitCall(call), "adapter returned null response");
            stream.finish();
            return response;
        }
        finally {
            interruptible = false;
            Thread.interrupted();
            call = null;
        }
    }

    private void recordUsage(String requestId, ModelResponse response)
    {
        response.usage().ifPresent(sample -> {
            tokens = Math.addExact(tokens, Math.addExact(sample.inputTokens(), sample.outputTokens()));
            emit(new ProviderAgentEvent.Usage(new ProviderUsageSample(Optional.of(requestId), ProviderUsageSample.Semantics.DELTA,
                    sample.inputTokens(), sample.cachedInputTokens(), sample.outputTokens(), sample.cost()), source(requestId)));
        });
    }

    private ApiPromptSnapshot prompt()
    {
        ModelHistory history = session.store.state().history();
        Optional<Instant> deadline = command.maximumRuntime().isZero() ? Optional.empty() : Optional.of(started.plus(command.maximumRuntime()));
        PromptContext context = new PromptContext()
        {
            @Override
            public String turnId() { return command.turnId(); }

            @Override
            public String model() { return model; }

            @Override
            public Optional<String> effort() { return effort; }

            @Override
            public ModelInput.UserMessage input() { return input; }

            @Override
            public ModelHistory history() { return history; }

            @Override
            public Optional<Instant> deadline() { return deadline; }

            @Override
            public boolean cancelled() { return stopping.get() != null; }
        };
        ApiPromptSnapshot snapshot;
        try {
            checkStop();
            interruptible = true;
            String instructions = session.engine.prompts.assemble(session.context, command, model, effort, session.tools);
            snapshot = session.prompts.capture(instructions, context, config.maximumTextChars, this::checkStop);
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
        checkStop();
        return snapshot;
    }

    private String runLoop(ModelInput initial, ApiRequestSetup setup)
    {
        ModelInput nextInput = initial;
        while (true) {
            checkStop();
            String requestId = UUID.randomUUID().toString();
            ModelRequest request = prepareRequest(requestId, setup, nextInput);
            reserveRequest(request.maximumOutputTokens(), false);
            ApiCheckpoint before = session.store.state();
            save(before.submitting(requestId, setup, nextInput));
            ModelResponse response;
            try {
                response = generate(request, new StreamText(requestId, true, config.maximumTextChars));
            }
            catch (ModelClientException failure) {
                if (!failure.deliveryUncertain()) {
                    save(before);
                }
                throw failure;
            }
            if (response.text().length() > config.maximumTextChars || response.replayState().payload().length() > config.maximumCheckpointBytes) {
                throw new Stopped("API response exceeded its size budget");
            }
            save(session.store.state().observed(requestId, response));
            observe(() -> new EngineObservation.ModelCompleted(observedTurnId(), requestId, response.stopReason(), response.usage().map(sample ->
                    new ProviderUsageSample(Optional.of(requestId), sample.semantics(), sample.inputTokens(), sample.cachedInputTokens(), sample.outputTokens(), sample.cost()))));
            recordUsage(requestId, response);
            emit(new ProviderAgentEvent.Message(Optional.empty(), response.stopReason() == ModelResponse.StopReason.TOOL_CALLS
                    ? ProviderAgentEvent.MessageKind.COMMENTARY : ProviderAgentEvent.MessageKind.ANSWER,
                    ProviderAgentEvent.MessagePhase.FINAL, config.redactor().redact(response.text()), source(requestId)));
            checkStop();
            if (response.stopReason() == ModelResponse.StopReason.COMPLETED) {
                return config.redactor().redact(response.text());
            }
            if (response.stopReason() == ModelResponse.StopReason.REFUSED) {
                throw new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, "Model refused the request", false);
            }
            if (response.stopReason() != ModelResponse.StopReason.TOOL_CALLS) {
                throw new Stopped("Model stopped with " + response.stopReason().name());
            }
            if (tokens >= config.maximumTokens) {
                throw new Stopped("API turn token budget exhausted");
            }
            nextInput = results(false);
        }
    }

    private ModelResponse awaitCall(ModelCall active)
    {
        while (true) {
            try {
                return active.completion().toCompletableFuture().get();
            }
            catch (InterruptedException failure) {
                // Completion is the adapter's quiescence fence; interruption alone cannot release this session's writer.
                active.cancel();
            }
            catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof CancellationException) {
                    throw new Stopped(stopping.get() == null ? "Model call cancelled" : stopping.get());
                }
                if (cause instanceof ModelClientException normalized) {
                    throw normalized;
                }
                throw new ModelClientException(ModelClientException.Kind.PROTOCOL, "Model call failed without a normalized outcome", true);
            }
            catch (CancellationException failure) {
                throw new Stopped(stopping.get() == null ? "Model call cancelled" : stopping.get());
            }
        }
    }

    private ModelInput.ToolResults results(boolean recovering)
    {
        if (!recovering && config.maximumParallelTools > 1) {
            List<ApiToolReceipt> receipts = session.store.state().receipts();
            Iterable<ToolDispatcher.Invocation> batch = () -> IntStream.range(0, receipts.size())
                    .filter(index -> receipts.get(index).result().isEmpty())
                    .mapToObj(index -> {
                        ApiToolReceipt receipt = receipts.get(index);
                        if (receipt.started()) {
                            throw new Stopped("Tool outcome is uncertain; reconciliation is required");
                        }
                        return invocation(index, receipt);
                    }).iterator();
            dispatch(() -> toolTurn.dispatchBatch(batch, config.maximumParallelTools));
        }
        List<ModelInput.ToolResult> results = new ArrayList<>();
        int count = session.store.state().receipts().size();
        for (int index = 0; index < count; index++) {
            checkStop();
            ApiToolReceipt receipt = session.store.state().receipts().get(index);
            if (receipt.result().isEmpty()) {
                if (receipt.started()) {
                    throw new Stopped("Tool outcome is uncertain; reconciliation is required");
                }
                if (recovering) {
                    recordResult(index, "Not executed: previous turn was interrupted", true);
                }
                else {
                    execute(index, receipt);
                }
                receipt = session.store.state().receipts().get(index);
            }
            ToolRegistration.Result outcome = receipt.result().orElseThrow();
            results.add(new ModelInput.ToolResult(receipt.id(), outcome.text(), outcome.error()));
        }
        return new ModelInput.ToolResults(results);
    }

    private void execute(int index, ApiToolReceipt receipt)
    {
        ToolDispatcher.Invocation invocation = invocation(index, receipt);
        dispatch(() -> toolTurn.dispatch(invocation.call(), invocation.context()));
    }

    private ToolDispatcher.Invocation invocation(int index, ApiToolReceipt receipt)
    {
        ToolDispatcher.Call invocation = receipt.invocation();
        return new ToolDispatcher.Invocation(invocation, new ToolDispatcher.Context()
        {
            @Override
            public String invocationId() { return invocation.eventId(); }

            @Override
            public Optional<Instant> deadline()
            {
                return command.maximumRuntime().isZero() ? Optional.empty() : Optional.of(started.plus(command.maximumRuntime()));
            }

            @Override
            public void checkStop() { ApiTurn.this.checkStop(); }

            @Override
            public boolean cancelled() { return stopping.get() != null; }

            @Override
            public ToolRequirements grants()
            {
                return session.grants;
            }

            @Override
            public boolean allowed(ToolDispatcher.Call call) { return ApiTurn.this.allowed(call); }

            @Override
            public Optional<EngineObservation.Admission> policyRejection(ToolDispatcher.Call call, ToolRequirements requirements)
            {
                return ApiTurn.this.policyRejection(call, requirements);
            }

            @Override
            public void decided(ToolDispatcher.Call call, EngineObservation.Admission admission)
            {
                observe(() -> new EngineObservation.ToolDecision(observedTurnId(), config.redactor().redact(call.eventId()),
                        config.redactor().redact(call.name()), admission));
            }

            @Override
            public String rejection(String code, String legacyMessage)
            {
                return session.builtins.isPresent() ? BuiltinTools.failure(code, code.equals("INVALID_ARGUMENTS")
                        ? "Unknown tool or invalid tool arguments" : legacyMessage) : legacyMessage;
            }

            @Override
            public void started(ToolDispatcher.Call call)
            {
                emit(new ProviderAgentEvent.Tool(Optional.empty(), call.name(), ProviderAgentEvent.ActivityState.STARTED,
                        "Host tool started", source(call.eventId())));
            }

            @Override
            public void recordStarted(ToolDispatcher.Call call)
            {
                update(state -> state.started(index));
            }

            @Override
            public void recordResult(ToolRegistration.Result outcome) { ApiTurn.this.recordResult(index, outcome.text(), outcome.error()); }

            @Override
            public void completed(ToolDispatcher.Call call, ToolRegistration.Result outcome)
            {
                observe(() -> new EngineObservation.ToolCompleted(observedTurnId(), config.redactor().redact(call.eventId()),
                        config.redactor().redact(call.name()), outcome.error()));
                emit(new ProviderAgentEvent.Tool(Optional.empty(), call.name(),
                        outcome.error() ? ProviderAgentEvent.ActivityState.FAILED : ProviderAgentEvent.ActivityState.COMPLETED,
                        outcome.error() ? "Host tool failed" : "Host tool completed", source(call.eventId()), Optional.empty(), Optional.empty(),
                        !outcome.error() && outcome.text().length() <= config.maximumToolOutputChars
                                ? Optional.of(new ProviderAgentEvent.ToolOutput(call.name(), config.redactor().redact(outcome.text()))) : Optional.empty()));
            }

            @Override
            public void recordPrepared(ToolResource resource)
            {
                update(state -> state.append(new ApiJournal.PreparedResource(invocation.id(), resource)));
            }

            @Override
            public void progress(String message)
            {
                emit(new ProviderAgentEvent.Tool(Optional.empty(), invocation.name(), ProviderAgentEvent.ActivityState.STARTED,
                        config.redactor().redact(message), source(invocation.eventId())));
            }

            @Override
            public void interruptible(boolean value) { interruptible = value; }
        });
    }

    private void dispatch(Runnable action)
    {
        try {
            action.run();
        }
        catch (ToolDispatcher.Limit failure) {
            throw new Stopped(failure.getMessage());
        }
        catch (ToolDispatcher.Uncertain failure) {
            session.damaged = true;
            throw new ModelClientException(ModelClientException.Kind.PROTOCOL, failure.getMessage(), true);
        }
    }

    private boolean allowed(ToolDispatcher.Call tool)
    {
        ProviderApprovalRequest request = new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Run " + tool.name(),
                config.redactor().redact(tool.arguments()), List.of(tool.name()),
                List.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), false,
                source(tool.eventId()), Instant.now());
        Duration wait = Duration.ofMinutes(5);
        if (!command.maximumRuntime().isZero()) {
            Duration remaining = Duration.between(Instant.now(), started.plus(command.maximumRuntime()));
            if (remaining.isNegative() || remaining.isZero()) {
                throw new Stopped("API turn deadline exceeded");
            }
            if (remaining.compareTo(wait) < 0) {
                wait = remaining;
            }
        }
        try {
            interruptible = true;
            approval = session.context.approvals().request(request, wait).toCompletableFuture();
            if (stopping.get() != null) {
                approval.cancel(false);
            }
            ProviderApprovalDecision decision = (ProviderApprovalDecision) approval.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return decision.decision() == ProviderApprovalDecision.Decision.ALLOW && !decision.timedOut() && decision.scopedPermission().isEmpty();
        }
        catch (InterruptedException | CancellationException | TimeoutException failure) {
            throw new Stopped(stopping.get() == null ? "Tool approval was interrupted or timed out" : stopping.get());
        }
        catch (ExecutionException failure) {
            return false;
        }
        finally {
            interruptible = false;
            Thread.interrupted();
            CompletableFuture<?> pending = approval;
            if (pending != null && !pending.isDone()) {
                pending.cancel(false);
            }
            approval = null;
        }
    }

    private Optional<EngineObservation.Admission> policyRejection(ToolDispatcher.Call call, ToolRequirements requirements)
    {
        if (session.extensions.isEmpty()) {
            return Optional.empty();
        }
        ToolPolicyContext context = new ToolPolicyContext()
        {
            @Override
            public String turnId() { return command.turnId(); }

            @Override
            public String invocationId() { return call.eventId(); }

            @Override
            public String toolName() { return call.name(); }

            @Override
            public ToolRequirements requirements() { return requirements; }

            @Override
            public Optional<Instant> deadline()
            {
                return command.maximumRuntime().isZero() ? Optional.empty() : Optional.of(started.plus(command.maximumRuntime()));
            }

            @Override
            public boolean cancelled() { return stopping.get() != null; }
        };
        try {
            interruptible = true;
            checkStop();
            return session.extensions.rejection(context, this::checkStop);
        }
        finally {
            interruptible = false;
            Thread.interrupted();
        }
    }

    private void recordResult(int index, String text, boolean error)
    {
        String bounded = config.redactor().redact(text);
        if (bounded.length() > config.maximumToolOutputChars) {
            String marker = "[tool output truncated]";
            bounded = bounded.substring(0, Math.max(0, config.maximumToolOutputChars - marker.length()))
                    + marker.substring(0, Math.min(marker.length(), config.maximumToolOutputChars));
        }
        String captured = bounded;
        update(state -> state.completed(index, captured, error));
    }

    private void update(UnaryOperator<ApiCheckpoint> transition)
    {
        synchronized (session.store) {
            save(transition.apply(session.store.state()));
        }
    }

    private void save(ApiCheckpoint state)
    {
        synchronized (session.store) {
            try {
                session.store.save(state);
            }
            catch (RuntimeException failure) {
                session.damaged = true;
                throw failure;
            }
        }
    }

    private final class StreamText
    {
        private final String id;
        private final boolean visible;
        private final int maximum;
        private int characters;
        private String held = "";

        StreamText(String id, boolean visible, int maximum)
        {
            this.id = id;
            this.visible = visible;
            this.maximum = maximum;
        }

        synchronized void text(String delta)
        {
            requireNonNull(delta, "delta is null");
            if (delta.length() > config.maximumTextChars - textChars || delta.length() > maximum - characters) {
                throw new ModelClientException(ModelClientException.Kind.PROTOCOL, "API stream exceeded its text budget", true);
            }
            textChars += delta.length();
            characters += delta.length();
            if (!visible) {
                return;
            }
            SecretRedactor.Chunk chunk = config.redactor().redactChunk(held, delta);
            held = chunk.held();
            if (!chunk.text().isEmpty()) {
                emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.PARTIAL, chunk.text(), source(id)));
            }
        }

        synchronized void finish()
        {
            if (!held.isEmpty()) {
                emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.PARTIAL, config.redactor().redact(held), source(id)));
                held = "";
            }
        }
    }
    private static final class Stopped
            extends RuntimeException
    {
        Stopped(String reason) { super(reason); }
    }}

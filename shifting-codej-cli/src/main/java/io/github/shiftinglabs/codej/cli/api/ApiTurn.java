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
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
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

    private void emit(ProviderAgentEvent event)
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
            emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.TURN_STARTED, "API turn started", source("turn")));
            ApiCheckpoint saved = session.store.state();
            if (saved.phase() == ApiCheckpoint.Phase.TOOLS) {
                // Reconcile an interrupted batch before adding fresh user input to the provider's transcript.
                runLoop(results(true), saved.setup().orElseThrow());
            }
            String answer = runLoop(input, requestSetup());
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
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
        try {
            session.finished();
        }
        catch (RuntimeException failure) {
            terminal = terminal(AgentTurnResult.State.FAILED, Optional.empty(), Optional.of("API session writer could not be released"), Optional.empty(), false);
        }
        result.complete(terminal);
    }

    private AgentTurnResult terminal(AgentTurnResult.State state, Optional<String> answer, Optional<String> reason,
            Optional<AgentTurnFailureKind> kind, boolean transientFailure)
    {
        return new AgentTurnResult(state, answer, reason, Duration.ofNanos(System.nanoTime() - startedNanos), true, kind, transientFailure);
    }

    private ApiRequestSetup requestSetup()
    {
        String instructions = session.engine.prompts.assemble(session.context, command, model, effort, session.tools);
        return new ApiRequestSetup(model, instructions, effort, command.serviceTier(), session.tools.definitions(), Optional.of(session.tools.contracts()));
    }

    private String runLoop(ModelInput initial, ApiRequestSetup setup)
    {
        ModelInput nextInput = initial;
        while (true) {
            checkStop();
            if (++requests > config.maximumRequests || tokens >= config.maximumTokens) {
                throw new Stopped("API turn request or token budget exhausted");
            }
            String requestId = UUID.randomUUID().toString();
            ApiCheckpoint before = session.store.state();
            ModelRequest.Builder builder = ModelRequest.builder(requestId, setup.model(), nextInput,
                    (int) Math.min(config.maximumOutputTokens, config.maximumTokens - tokens)).instructions(setup.instructions()).tools(setup.tools());
            before.replay().ifPresent(builder::replayState);
            setup.effort().ifPresent(builder::effort);
            setup.tier().ifPresent(builder::serviceTier);
            if (!command.maximumRuntime().isZero()) {
                builder.deadline(started.plus(command.maximumRuntime()));
            }
            save(before.submitting(requestId, setup, nextInput));
            ModelResponse response;
            StreamText stream = new StreamText(requestId);
            try {
                checkStop();
                interruptible = true;
                call = session.engine.client.generate(builder.build(), stream::text);
                interruptible = false;
                Thread.interrupted();
                if (stopping.get() != null) {
                    call.cancel();
                }
                response = awaitCall(call);
                stream.finish();
            }
            catch (ModelClientException failure) {
                if (!failure.deliveryUncertain()) {
                    save(before);
                }
                throw failure;
            }
            finally {
                interruptible = false;
                Thread.interrupted();
                call = null;
            }
            if (response.text().length() > config.maximumTextChars || response.replayState().payload().length() > config.maximumCheckpointBytes) {
                throw new Stopped("API response exceeded its size budget");
            }
            save(session.store.state().observed(requestId, response));
            response.usage().ifPresent(sample -> {
                tokens = Math.addExact(tokens, Math.addExact(sample.inputTokens(), sample.outputTokens()));
                emit(new ProviderAgentEvent.Usage(new ProviderUsageSample(Optional.of(requestId), ProviderUsageSample.Semantics.DELTA,
                        sample.inputTokens(), sample.cachedInputTokens(), sample.outputTokens(), sample.cost()), source(requestId)));
            });
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
        ToolDispatcher.Call invocation = receipt.invocation();
        try {
            toolTurn.dispatch(invocation, new ToolDispatcher.Context()
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
                    save(session.store.state().started(index));
                }

                @Override
                public void recordResult(ToolRegistration.Result outcome) { ApiTurn.this.recordResult(index, outcome.text(), outcome.error()); }

                @Override
                public void completed(ToolDispatcher.Call call, ToolRegistration.Result outcome)
                {
                    emit(new ProviderAgentEvent.Tool(Optional.empty(), call.name(),
                            outcome.error() ? ProviderAgentEvent.ActivityState.FAILED : ProviderAgentEvent.ActivityState.COMPLETED,
                            outcome.error() ? "Host tool failed" : "Host tool completed", source(call.eventId()), Optional.empty(), Optional.empty(),
                            !outcome.error() && outcome.text().length() <= config.maximumToolOutputChars
                                    ? Optional.of(new ProviderAgentEvent.ToolOutput(call.name(), config.redactor().redact(outcome.text()))) : Optional.empty()));
                }

                @Override
                public void recordPrepared(ToolResource resource)
                {
                    save(session.store.state().append(new ApiJournal.PreparedResource(invocation.id(), resource)));
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

    private void recordResult(int index, String text, boolean error)
    {
        String bounded = config.redactor().redact(text);
        if (bounded.length() > config.maximumToolOutputChars) {
            String marker = "[tool output truncated]";
            bounded = bounded.substring(0, Math.max(0, config.maximumToolOutputChars - marker.length()))
                    + marker.substring(0, Math.min(marker.length(), config.maximumToolOutputChars));
        }
        save(session.store.state().completed(index, bounded, error));
    }

    private void save(ApiCheckpoint state)
    {
        try {
            session.store.save(state);
        }
        catch (RuntimeException failure) {
            session.damaged = true;
            throw failure;
        }
    }

    private final class StreamText
    {
        private final String id;
        private String held = "";

        StreamText(String id) { this.id = id; }

        synchronized void text(String delta)
        {
            requireNonNull(delta, "delta is null");
            if (delta.length() > config.maximumTextChars - textChars) {
                throw new ModelClientException(ModelClientException.Kind.PROTOCOL, "API stream exceeded its text budget", true);
            }
            textChars += delta.length();
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

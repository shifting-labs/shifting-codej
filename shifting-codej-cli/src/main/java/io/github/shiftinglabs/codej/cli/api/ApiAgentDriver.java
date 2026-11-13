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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnFailureKind;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.driver.ProviderTurnRef;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.shiftinglabs.codej.cli.api.ApiSessionStore.JSON;
import static java.util.Objects.requireNonNull;

/**
 * Shared direct-API runtime. Owns its model client and default bounded workers; borrows all
 * ConnectorContext services. Supports TEXT_ONLY/TOOLS_ONLY with trusted host callbacks, not
 * filesystem/shell sandboxing. No provider process, native tool or subagent is started.
 */
public final class ApiAgentDriver
        implements ConnectorDriver
{
    private static final ThreadLocal<ApiSession> CALLBACK = new ThreadLocal<>();
    private final ApiEngineConfig config;
    private final Path profile;
    private final ModelClient client;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final Set<ApiSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ApiAgentDriver(ApiEngineConfig config, ConnectorContext context)
    {
        this.config = requireNonNull(config, "config is null");
        profile = requireNonNull(context, "context is null").profileDirectory();
        client = requireNonNull(config.clients.get(), "client factory returned null");
        workers = config.executor.orElseGet(() -> new ThreadPoolExecutor(config.maximumSessions, config.maximumSessions, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maximumSessions), Thread.ofPlatform().daemon().name("codej-api-worker-", 0).factory()));
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("codej-api-deadline-", 0).factory());
    }

    @Override
    public ConnectorIdentity identity() { return config.identity; }

    @Override
    public AgentLaneId id() { return config.lane; }

    @Override
    public String label() { return config.identity.connectorId(); }

    @Override
    public AgentCapabilities capabilities()
    {
        requireOpen();
        return new AgentCapabilities(false, false, false, Set.of(AgentPermissionPolicy.AUTO), client.capabilities(config.model).attachmentMediaTypes());
    }

    @Override
    public AgentProviderStatus probe() { return probe(Optional.empty()); }

    @Override
    public AgentProviderStatus probe(Optional<String> model)
    {
        requireOpen();
        AgentProviderStatus status = client.probe(requireNonNull(model, "model is null"));
        return new AgentProviderStatus(id(), status.readiness(), status.version(), config.redactor().redact(status.diagnostic()), capabilities());
    }

    @Override
    public List<ProviderModel> models()
    {
        requireOpen();
        return List.copyOf(client.models());
    }

    @Override
    public ProviderQuotaSnapshot quota()
    {
        requireOpen();
        return client.quota();
    }

    @Override
    public void validate(AgentSessionContext context)
    {
        requireOpen();
        requireNonNull(context, "context is null");
        var scope = context.scope();
        if ((context.access() != SessionAccess.TEXT_ONLY && context.access() != SessionAccess.TOOLS_ONLY) || scope.policy() != AgentPermissionPolicy.AUTO
                || context.review() || context.publicationProtected() || context.asksUser() || scope.readOnlySubagents() || scope.fixedSandbox()
                || scope.webSearch() || scope.commits() || scope.approveEveryCommand() || scope.approveEveryEdit() || !scope.writableRoots().isEmpty()
                || !scope.unsandboxedCommands().isEmpty() || !scope.unixSockets().isEmpty() || !scope.reviewerContext().isEmpty()
                || !scope.reviewerRules().isEmpty() || scope.reviewSandboxedCommands()
                || !scope.sessionDeniedCommands().isEmpty() || !scope.sessionDeniedReads().isEmpty()) {
            throw new AgentUnavailableException("API engine supports text and trusted host tools; requested constraints are unsupported");
        }
        new ApiToolRegistry(context.sessionTools());
        options(context.model().orElse(config.model), context.effort(), Optional.empty(), !context.sessionTools().isEmpty());
        context.sessionReference().ifPresent(reference -> {
            if (!reference.laneId().equals(id()) || reference.contractVersion() != 1 || !canonicalId(reference.providerValue())) {
                throw new AgentUnavailableException("API session reference belongs to another lane or version");
            }
        });
    }

    private ModelCapabilities options(String model, Optional<String> effort, Optional<String> tier, boolean tools)
    {
        ModelCapabilities supported;
        try {
            supported = client.capabilities(model);
        }
        catch (ModelClientException failure) {
            throw new AgentUnavailableException("Selected API model is unavailable");
        }
        if ((tools && !supported.toolCalls()) || effort.filter(value -> !supported.efforts().contains(value)).isPresent()
                || tier.filter(value -> !supported.serviceTiers().contains(value)).isPresent()) {
            throw new AgentUnavailableException("Selected model does not support the requested tools or options");
        }
        return supported;
    }

    @Override
    public synchronized AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        validate(context);
        requireNonNull(events, "events is null");
        if (sessions.size() >= config.maximumSessions) {
            throw new AgentUnavailableException("API session capacity reached");
        }
        String id = context.sessionReference().map(AgentSessionRef::providerValue).orElseGet(() -> UUID.randomUUID().toString());
        ApiSessionStore store = ApiSessionStore.open(profile, id, context.sessionReference().isPresent(), config);
        ApiSession session = new ApiSession(context, events, store, new AgentSessionRef(id(), 1, id));
        sessions.add(session);
        return session;
    }

    private void requireOpen()
    {
        if (closed) {
            throw new AgentUnavailableException("API driver is closed");
        }
    }

    private static boolean canonicalId(String id)
    {
        try {
            return UUID.fromString(id).toString().equals(id);
        }
        catch (IllegalArgumentException failure) {
            return false;
        }
    }

    @Override
    public void close()
    {
        synchronized (this) {
            closed = true;
        }
        List<ApiSession> owned = List.copyOf(sessions);
        owned.forEach(ApiSession::requestClose);
        if (CALLBACK.get() != null || owned.stream().anyMatch(session -> session.onWorker())) {
            throw new AgentUnavailableException("API shutdown cannot be confirmed from an active callback");
        }
        boolean stopped = true;
        for (ApiSession session : owned) {
            try {
                session.close();
            }
            catch (RuntimeException failure) {
                stopped = false;
            }
        }
        try {
            client.close();
        }
        catch (RuntimeException failure) {
            stopped = false;
        }
        timer.shutdownNow();
        if (config.executor.isEmpty()) {
            workers.shutdown();
            try {
                stopped &= workers.awaitTermination(config.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                stopped = false;
            }
        }
        if (!stopped || !sessions.isEmpty()) {
            throw new AgentUnavailableException("API shutdown could not confirm that local work stopped");
        }
    }

    private final class ApiSession
            implements AgentSession
    {
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final ApiSessionStore store;
        private final AgentSessionRef reference;
        private final ApiToolRegistry tools;
        private ApiTurn running;
        private boolean closing;
        private boolean released;
        private volatile boolean damaged;

        ApiSession(AgentSessionContext context, AgentEventSink events, ApiSessionStore store, AgentSessionRef reference)
        {
            this.context = context;
            this.events = events;
            this.store = store;
            this.reference = reference;
            tools = new ApiToolRegistry(context.sessionTools());
        }

        @Override
        public AgentSessionRef reference() { return reference; }

        @Override
        public synchronized boolean resumable() { return !damaged && recoverable(store.state()); }

        @Override
        public synchronized RunningTurn start(AgentTurnCommand command)
        {
            requireOpen();
            requireNonNull(command, "command is null");
            if (closing || running != null) {
                throw new AgentUnavailableException("API session is closed or already has an active turn");
            }
            ObjectNode before = store.state();
            if (damaged || !recoverable(before)) {
                throw new AgentUnavailableException("Previous API request or tool outcome is uncertain; reconciliation is required");
            }
            String model = command.model().or(context::model).orElse(config.model);
            Optional<String> effort = command.effort().or(context::effort);
            ModelCapabilities supported = options(model, effort, command.serviceTier(), !context.sessionTools().isEmpty());
            if (command.message().length() > config.maximumTextChars
                    || command.attachments().stream().anyMatch(item -> !supported.attachmentMediaTypes().contains(item.mediaType()))) {
                throw new AgentUnavailableException("API turn input exceeds its limits or uses unsupported attachments");
            }
            ModelInput.UserMessage input = store.stage(command.message(), command.attachments());
            if (before.path("phase").asText().equals("tools")) {
                var definitions = JSON.createArrayNode();
                tools.definitions().forEach(tool -> definitions.addObject().put("name", tool.name()).put("description", tool.description())
                        .put("schema", tool.inputSchema()));
                if (!definitions.equals(before.path("setup").path("tools"))) {
                    throw new AgentUnavailableException("Pending API tool batch requires its original tool definitions");
                }
            }
            ObjectNode next = before.deepCopy();
            next.withArray("journal").addObject().put("kind", "user").put("turn", command.turnId()).set("input", ApiSessionStore.input(input));
            store.save(next);
            ApiTurn turn = new ApiTurn(this, command, input, model, effort);
            running = turn;
            try {
                if (!command.maximumRuntime().isZero()) {
                    turn.timeout = timer.schedule(() -> turn.stop("API turn deadline exceeded"), command.maximumRuntime().toMillis(), TimeUnit.MILLISECONDS);
                }
                workers.execute(turn::run);
                return turn;
            }
            catch (RuntimeException failure) {
                if (turn.timeout != null) {
                    turn.timeout.cancel(false);
                }
                running = null;
                store.save(before);
                throw new AgentUnavailableException("API turn was not submitted");
            }
        }

        private synchronized void finished()
        {
            running = null;
            if (closing) {
                release();
            }
        }

        private synchronized void release()
        {
            if (!released) {
                store.close();
                released = true;
                sessions.remove(this);
            }
        }

        private synchronized boolean onWorker() { return running != null && running.runner == Thread.currentThread(); }

        private void requestClose()
        {
            ApiTurn turn;
            synchronized (this) {
                closing = true;
                turn = running;
            }
            if (turn != null) {
                turn.cancel();
            }
        }

        @Override
        public void close()
        {
            requestClose();
            ApiTurn turn;
            synchronized (this) {
                turn = running;
            }
            if (turn != null) {
                if (onWorker() || CALLBACK.get() == this) {
                    throw new AgentUnavailableException("API session stop cannot be confirmed from its callback");
                }
                try {
                    turn.result.get(config.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AgentUnavailableException("API session stop was interrupted");
                }
                catch (ExecutionException | TimeoutException failure) {
                    throw new AgentUnavailableException("API session still owns unconfirmed local work");
                }
            }
            release();
        }
    }

    private static boolean recoverable(ObjectNode state)
    {
        if (Set.of("request", "blocked").contains(state.path("phase").asText())) {
            return false;
        }
        for (JsonNode receipt : state.path("receipts")) {
            if (receipt.path("started").asBoolean() && !receipt.has("result")) {
                return false;
            }
        }
        return true;
    }

    private final class ApiTurn
            implements RunningTurn
    {
        private final ApiSession session;
        private final AgentTurnCommand command;
        private final ModelInput.UserMessage input;
        private final String model;
        private final Optional<String> effort;
        private final Instant started = Instant.now();
        private final long startedNanos = System.nanoTime();
        private final CompletableFuture<AgentTurnResult> result = new CompletableFuture<>();
        private final AtomicReference<String> stopping = new AtomicReference<>();
        private volatile ModelCall call;
        private volatile Thread runner;
        private volatile boolean interruptible;
        private volatile CompletableFuture<?> approval;
        private ScheduledFuture<?> timeout;
        private int requests;
        private int toolCalls;
        private long tokens;
        private int textChars;

        ApiTurn(ApiSession session, AgentTurnCommand command, ModelInput.UserMessage input, String model, Optional<String> effort)
        {
            this.session = session;
            this.command = command;
            this.input = input;
            this.model = model;
            this.effort = effort;
        }

        @Override
        public ProviderTurnRef reference() { return new ProviderTurnRef(command.turnId()); }

        @Override
        public CompletionStage<AgentTurnResult> completion() { return result.minimalCompletionStage(); }

        @Override
        public void cancel() { stop("API turn cancelled"); }

        private void stop(String reason)
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

        private void run()
        {
            runner = Thread.currentThread();
            AgentTurnResult terminal;
            try {
                checkStop();
                emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.TURN_STARTED, "API turn started", source("turn")));
                ObjectNode saved = session.store.state();
                if (saved.path("phase").asText().equals("tools")) {
                    // Reconcile an interrupted batch before adding fresh user input to the provider's transcript.
                    runLoop(results(true), (ObjectNode) saved.path("setup"));
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

        private ObjectNode requestSetup()
        {
            ObjectNode setup = JSON.createObjectNode().put("model", model)
                    .put("instructions", session.context.scope().standingInstructions() + "\n" + session.context.managedInstructions());
            effort.ifPresent(value -> setup.put("effort", value));
            command.serviceTier().ifPresent(value -> setup.put("tier", value));
            var tools = setup.putArray("tools");
            session.tools.definitions().forEach(tool -> tools.addObject().put("name", tool.name()).put("description", tool.description()).put("schema", tool.inputSchema()));
            return setup;
        }

        private String runLoop(ModelInput initial, ObjectNode setup)
        {
            ModelInput nextInput = initial;
            while (true) {
                checkStop();
                if (++requests > config.maximumRequests || tokens >= config.maximumTokens) {
                    throw new Stopped("API turn request or token budget exhausted");
                }
                String requestId = UUID.randomUUID().toString();
                ObjectNode before = session.store.state();
                ModelRequest.Builder builder = ModelRequest.builder(requestId, setup.path("model").asText(), nextInput,
                        (int) Math.min(config.maximumOutputTokens, config.maximumTokens - tokens)).instructions(setup.path("instructions").asText());
                List<ModelRequest.ToolDefinition> definitions = new ArrayList<>();
                setup.path("tools").forEach(tool -> definitions.add(new ModelRequest.ToolDefinition(
                        tool.path("name").asText(), tool.path("description").asText(), tool.path("schema").asText())));
                builder.tools(definitions);
                if (before.has("replay")) {
                    builder.replayState(ApiSessionStore.replay(before));
                }
                if (setup.has("effort")) {
                    builder.effort(setup.path("effort").asText());
                }
                if (setup.has("tier")) {
                    builder.serviceTier(setup.path("tier").asText());
                }
                if (!command.maximumRuntime().isZero()) {
                    builder.deadline(started.plus(command.maximumRuntime()));
                }
                ObjectNode submitting = before.deepCopy().put("phase", "request");
                submitting.set("setup", setup);
                submitting.set("input", ApiSessionStore.input(nextInput));
                submitting.withArray("journal").addObject().put("kind", "request").put("id", requestId);
                save(submitting);
                ModelResponse response;
                StreamText stream = new StreamText(requestId);
                try {
                    checkStop();
                    interruptible = true;
                    call = client.generate(builder.build(), stream::text);
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
                ObjectNode observed = session.store.state();
                ApiSessionStore.replay(observed, response.replayState());
                observed.put("phase", response.stopReason() == ModelResponse.StopReason.TOOL_CALLS ? "tools" : "ready");
                var receipts = observed.putArray("receipts");
                response.toolCalls().forEach(tool -> receipts.addObject().put("id", tool.id()).put("name", tool.name()).put("arguments", tool.arguments())
                        .put("eventId", requestId + ":" + tool.id()).put("started", false));
                observed.withArray("journal").addObject().put("kind", "response").put("id", requestId).put("stop", response.stopReason().name());
                save(observed);
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
            int count = session.store.state().withArray("receipts").size();
            for (int index = 0; index < count; index++) {
                checkStop();
                ObjectNode state = session.store.state();
                ObjectNode receipt = (ObjectNode) state.withArray("receipts").get(index);
                if (!receipt.has("result")) {
                    if (receipt.path("started").asBoolean()) {
                        throw new Stopped("Tool outcome is uncertain; reconciliation is required");
                    }
                    if (recovering) {
                        recordResult(index, "Not executed: previous turn was interrupted", true);
                    }
                    else {
                        execute(index, receipt);
                    }
                    receipt = (ObjectNode) session.store.state().withArray("receipts").get(index);
                }
                results.add(new ModelInput.ToolResult(receipt.path("id").asText(), receipt.path("result").path("text").asText(),
                        receipt.path("result").path("error").asBoolean()));
            }
            return new ModelInput.ToolResults(results);
        }

        private void execute(int index, ObjectNode receipt)
        {
            if (++toolCalls > config.maximumToolCalls) {
                throw new Stopped("API turn tool budget exhausted");
            }
            Tool tool;
            try {
                tool = session.tools.tool(receipt.path("name").asText(), receipt.path("arguments").asText());
            }
            catch (IllegalArgumentException failure) {
                recordResult(index, failure.getMessage(), true);
                return;
            }
            if (config.approveTools && !allowed(tool, receipt)) {
                recordResult(index, "Tool invocation denied", true);
                return;
            }
            checkStop();
            emit(new ProviderAgentEvent.Tool(Optional.empty(), tool.name(), ProviderAgentEvent.ActivityState.STARTED,
                    "Host tool started", source(receipt.path("eventId").asText(receipt.path("id").asText()))));
            checkStop();
            ObjectNode state = session.store.state();
            ((ObjectNode) state.withArray("receipts").get(index)).put("started", true);
            state.withArray("journal").addObject().put("kind", "tool-start").put("id", receipt.path("id").asText());
            save(state);
            if (stopping.get() != null) {
                recordResult(index, "Not executed: turn interrupted before handler invocation", true);
                checkStop();
            }
            String text;
            boolean error = false;
            try {
                interruptible = true;
                text = invokeTool(tool, receipt.path("arguments").asText());
            }
            catch (Exception failure) {
                text = "Host tool failed";
                error = true;
            }
            finally {
                interruptible = false;
                Thread.interrupted();
            }
            recordResult(index, text, error);
            emit(new ProviderAgentEvent.Tool(Optional.empty(), tool.name(), error ? ProviderAgentEvent.ActivityState.FAILED : ProviderAgentEvent.ActivityState.COMPLETED,
                    error ? "Host tool failed" : "Host tool completed", source(receipt.path("eventId").asText(receipt.path("id").asText())),
                    Optional.empty(), Optional.empty(),
                    !error && text.length() <= config.maximumToolOutputChars
                            ? Optional.of(new ProviderAgentEvent.ToolOutput(tool.name(), config.redactor().redact(text))) : Optional.empty()));
        }

        private String invokeTool(Tool tool, String arguments)
                throws Exception
        {
            if (config.toolExecutor.isEmpty()) {
                return requireNonNull(tool.call(arguments), "tool returned null");
            }
            FutureTask<String> task = new FutureTask<>(() -> {
                checkStop();
                return requireNonNull(tool.call(arguments), "tool returned null");
            });
            config.toolExecutor.orElseThrow().execute(task);
            // Future cancellation does not prove that trusted host code stopped. Keep the writer until it actually returns.
            while (true) {
                try {
                    return task.get();
                }
                catch (InterruptedException ignored) {
                    // The turn's stop flag is checked before the queued handler begins and after its receipt is saved.
                }
            }
        }

        private boolean allowed(Tool tool, ObjectNode receipt)
        {
            ProviderApprovalRequest request = new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Run " + tool.name(),
                    config.redactor().redact(receipt.path("arguments").asText()), List.of(tool.name()),
                    List.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), false,
                    source(receipt.path("eventId").asText(receipt.path("id").asText())), Instant.now());
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
            ObjectNode state = session.store.state();
            ObjectNode receipt = (ObjectNode) state.withArray("receipts").get(index);
            receipt.putObject("result").put("text", bounded).put("error", error);
            state.withArray("journal").addObject().put("kind", "tool-result").put("id", receipt.path("id").asText()).put("text", bounded).put("error", error);
            save(state);
        }

        private void save(ObjectNode state)
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
    }

    private static final class Stopped
            extends RuntimeException
    {
        Stopped(String reason) { super(reason); }
    }
}

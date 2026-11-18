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
package io.github.shiftinglabs.codej.cli.antigravity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Ascii;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionOpenException;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnFailureKind;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import io.github.shiftinglabs.codej.cli.driver.ProviderTurnRef;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpClient.await;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpClient.object;
import static io.github.shiftinglabs.codej.cli.driver.AgentTurnResult.State.CANCELLED;
import static io.github.shiftinglabs.codej.cli.driver.AgentTurnResult.State.COMPLETED;
import static io.github.shiftinglabs.codej.cli.driver.AgentTurnResult.State.FAILED;
import static io.github.shiftinglabs.codej.cli.driver.AgentTurnResult.State.INTERRUPTED;
import static java.util.Objects.requireNonNull;

final class AntigravitySession
        implements AgentSession
{
    private static final int MAX_ANSWER_LENGTH = 4 * 1024 * 1024;

    private final ProcessSupervisor supervisor;
    private final Supplier<ProcessSpec> launch;
    private AgentSessionContext context;
    private AgentEventSink events;
    private final Duration startupTimeout;
    private final Consumer<String> recordNewSession;
    private final Runnable onClose;
    private SecretRedactor redactor;
    private volatile AgentSessionRef reference;
    private volatile AcpTurn turn;
    private volatile boolean saved;
    private AcpClient client;
    private JsonNode configOptions;
    private JsonNode models;
    private boolean canCloseSession;
    private boolean closed;
    private boolean released;

    private AntigravitySession(ProcessSupervisor supervisor, Supplier<ProcessSpec> launch, AgentSessionContext context, AgentEventSink events,
            Duration startupTimeout, Consumer<String> recordNewSession, Runnable onClose)
    {
        this.supervisor = supervisor;
        this.launch = launch;
        this.context = context;
        this.events = events;
        this.startupTimeout = startupTimeout;
        this.recordNewSession = recordNewSession;
        this.onClose = onClose;
        this.reference = context.sessionReference().orElse(null);
    }

    static AntigravitySession open(ProcessSupervisor supervisor, Supplier<ProcessSpec> launch, AgentSessionContext context, AgentEventSink events,
            Duration startupTimeout, Consumer<String> recordNewSession, Runnable onClose)
    {
        AntigravitySession session = new AntigravitySession(supervisor, launch, context, events, startupTimeout, recordNewSession, onClose);
        session.connect();
        return session;
    }

    private void connect()
    {
        ProcessSpec spec = launch.get();
        redactor = SecretRedactor.fromEnvironment(spec.environment());
        client = new AcpClient(supervisor, spec, this::update);
        try {
            ObjectNode initialize = object().put("protocolVersion", 1);
            initialize.putObject("clientCapabilities");
            initialize.putObject("clientInfo").put("name", "shifting-codej").put("version", "1");
            JsonNode hello = await(client.request("initialize", initialize), startupTimeout);
            if (!hello.path("protocolVersion").isIntegralNumber() || hello.get("protocolVersion").intValue() != 1) {
                throw new AgentUnavailableException("Antigravity does not support ACP version 1");
            }
            JsonNode capabilities = hello.path("agentCapabilities").path("sessionCapabilities");
            canCloseSession = capabilities.path("close").isObject();
            ObjectNode params = object().put("cwd", context.worktree().toString());
            params.putArray("mcpServers");
            JsonNode setup;
            if (reference == null) {
                setup = await(client.request("session/new", params), startupTimeout);
                String id = setup.path("sessionId").textValue();
                if (id == null || id.isBlank()) {
                    throw new AgentUnavailableException("Antigravity returned an empty session ID");
                }
                reference = new AgentSessionRef(AntigravityCliDriver.LANE, 1, id);
                recordNewSession.accept(id);
            }
            else {
                if (!capabilities.path("resume").isObject()) {
                    throw new AgentUnavailableException("Antigravity does not support ACP session resume");
                }
                setup = await(client.request("session/resume", params.put("sessionId", reference.providerValue())), startupTimeout);
                saved = true;
            }
            configOptions = setup.path("configOptions");
            models = setup.path("models");
        }
        catch (RuntimeException failure) {
            try {
                client.stop();
            }
            catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
                throw new AgentSessionOpenException("Antigravity session failed to open and its process may still be running", failure, this::close);
            }
            client = null;
            throw failure;
        }
    }

    @Override
    public AgentSessionRef reference()
    {
        return reference;
    }

    @Override
    public boolean resumable()
    {
        return saved;
    }

    @Override
    public synchronized boolean prepareTurn(AgentSessionContext next, AgentEventSink sink)
    {
        requireNonNull(next, "context is null");
        requireNonNull(sink, "events is null");
        // A caller may select a model on its first turn, before this new session has written any resumable history.
        if (closed || client == null || turn != null || !next.sessionReference().equals(Optional.of(reference))
                || !next.taskId().equals(context.taskId()) || !next.worktree().equals(context.worktree())
                || !next.providerDirectory().equals(context.providerDirectory()) || !next.runtimeDirectory().equals(context.runtimeDirectory())
                || !next.scope().equals(context.scope()) || next.asksUser() != context.asksUser()
                || !next.managedInstructions().equals(context.managedInstructions()) || !next.sessionTools().equals(context.sessionTools())) {
            return false;
        }
        context = next;
        events = sink;
        return true;
    }

    @Override
    public synchronized RunningTurn start(AgentTurnCommand command)
    {
        checkState(!closed, "The Antigravity session is closed");
        checkState(turn == null, "Another Antigravity turn is active");
        checkArgument(command.attachments().isEmpty(), "Antigravity accepts text input only");
        checkArgument(command.serviceTier().isEmpty(), "Antigravity does not support service tiers");
        if (client == null) {
            connect();
        }
        command.model().or(context::model).ifPresent(this::selectModel);
        command.effort().or(context::effort).ifPresent(value -> configure("thought_level", value));
        ObjectNode params = sessionParams();
        params.putArray("prompt").addObject().put("type", "text").put("text", command.message());
        AcpTurn next = new AcpTurn(new ProviderTurnRef(command.turnId()), client);
        events.emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.TURN_STARTED, "Antigravity turn started"));
        turn = next;
        CompletableFuture<JsonNode> reply;
        try {
            reply = client.request("session/prompt", params);
        }
        catch (RuntimeException failure) {
            turn = null;
            throw failure;
        }
        if (!command.maximumRuntime().isZero()) {
            reply.orTimeout(TimeUnit.NANOSECONDS.convert(command.maximumRuntime()), TimeUnit.NANOSECONDS);
        }
        reply.whenComplete(next::result);
        return next;
    }

    private ObjectNode sessionParams()
    {
        return object().put("sessionId", reference.providerValue());
    }

    private void selectModel(String value)
    {
        // agy models lists full slugs (including effort). Its ACP config option instead takes the base model.
        for (JsonNode model : models.path("availableModels")) {
            if (value.equals(model.path("modelId").textValue())) {
                await(client.request("session/set_model", sessionParams().put("modelId", value)), startupTimeout);
                return;
            }
        }
        configure("model", value);
    }

    private void configure(String category, String value)
    {
        for (JsonNode option : configOptions) {
            if (category.equals(option.path("category").textValue()) || category.equals(option.path("id").textValue())) {
                ObjectNode params = sessionParams().put("configId", option.path("id").asText()).put("value", value);
                configOptions = await(client.request("session/set_config_option", params), startupTimeout).path("configOptions");
                return;
            }
        }
        throw new AgentUnavailableException("Antigravity did not offer the requested " + category + " setting");
    }

    private void update(AcpClient sender, JsonNode params)
    {
        AcpTurn current = turn;
        if (current != null && current.child == sender && reference.providerValue().equals(params.path("sessionId").textValue())) {
            current.update(params.path("update"));
        }
    }

    @Override
    public void close()
    {
        AcpClient child;
        AcpTurn current;
        synchronized (this) {
            closed = true;
            child = client;
            current = turn;
        }
        if (current != null) {
            current.cancel();
            try {
                await(current.cleaned, Duration.ofSeconds(40));
            }
            catch (AgentUnavailableException ignored) {
                // Retry a failed verification, or stop a child stuck draining its input, before releasing ownership.
            }
        }
        if (child != null) {
            child.stop();
            if (current == null) {
                child.awaitDrained();
            }
        }
        synchronized (this) {
            if (!released) {
                released = true;
                onClose.run();
            }
        }
    }

    private final class AcpTurn
            implements RunningTurn
    {
        private final ProviderTurnRef id;
        private final AcpClient child;
        private final ProviderAgentEventSource source;
        private final long started = System.nanoTime();
        private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> cleaned = new CompletableFuture<>();
        private final StringBuilder answer = new StringBuilder();
        private final Map<String, String> tools = new HashMap<>();
        private String held = "";
        private Optional<ProviderUsageSample> usage = Optional.empty();
        private boolean terminal;

        private AcpTurn(ProviderTurnRef id, AcpClient child)
        {
            this.id = id;
            this.child = child;
            this.source = new ProviderAgentEventSource(Optional.of(reference.providerValue()), Optional.empty(), Optional.empty(),
                    Optional.of(id.value()), Optional.of(id.value()));
        }

        @Override
        public ProviderTurnRef reference()
        {
            return id;
        }

        @Override
        public CompletionStage<AgentTurnResult> completion()
        {
            return completion.minimalCompletionStage();
        }

        private void update(JsonNode update)
        {
            ProviderAgentEvent event = mapUpdate(update);
            if (event != null) {
                events.emit(event);
            }
        }

        private synchronized ProviderAgentEvent mapUpdate(JsonNode update)
        {
            if (terminal) {
                return null;
            }
            switch (update.path("sessionUpdate").asText()) {
                case "agent_message_chunk" -> {
                    JsonNode content = update.path("content");
                    if ("text".equals(content.path("type").textValue()) && content.path("text").isTextual()) {
                        SecretRedactor.Chunk chunk = redactor.redactChunk(held, content.get("text").textValue());
                        if (chunk.text().length() + chunk.held().length() > MAX_ANSWER_LENGTH - answer.length()) {
                            throw new AgentUnavailableException("Antigravity response exceeds the output limit");
                        }
                        held = chunk.held();
                        answer.append(chunk.text());
                        if (!chunk.text().isEmpty()) {
                            return new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.PARTIAL, chunk.text(), source);
                        }
                    }
                }
                case "tool_call", "tool_call_update" -> {
                    String item = update.path("toolCallId").asText("");
                    String title = Ascii.truncate(redactor.redact(update.path("title").asText(tools.getOrDefault(item, "Tool"))), 1024, "...");
                    if (item.isBlank() || item.length() > 1024 || title.isBlank()) {
                        return null;
                    }
                    ProviderAgentEvent.ActivityState state = switch (update.path("status").asText("")) {
                        case "completed" -> ProviderAgentEvent.ActivityState.COMPLETED;
                        case "failed" -> ProviderAgentEvent.ActivityState.FAILED;
                        case "pending" -> ProviderAgentEvent.ActivityState.STARTED;
                        default -> "tool_call".equals(update.path("sessionUpdate").asText()) ? ProviderAgentEvent.ActivityState.STARTED : ProviderAgentEvent.ActivityState.PROGRESS;
                    };
                    if (state == ProviderAgentEvent.ActivityState.COMPLETED || state == ProviderAgentEvent.ActivityState.FAILED) {
                        tools.remove(item);
                    }
                    else if (tools.size() < 1024) {
                        tools.put(item, title);
                    }
                    return new ProviderAgentEvent.Tool(Optional.empty(), title, state, title, source.withItem(Optional.of(redactor.redact(item))));
                }
                default -> {}
            }
            return null;
        }

        private synchronized void result(JsonNode reply, Throwable failure)
        {
            if (terminal) {
                return;
            }
            if (failure instanceof TimeoutException) {
                stopTurn(INTERRUPTED, "Antigravity turn timed out");
            }
            else if (failure instanceof AcpClient.RpcException rpc) {
                finish(FAILED, rpc.getMessage(), rpc.authenticationRequired() ? Optional.of(AgentTurnFailureKind.AUTHENTICATION_REQUIRED) : Optional.empty());
            }
            else if (failure != null) {
                finish(FAILED, "Antigravity lost the ACP reply after sending the prompt", Optional.of(AgentTurnFailureKind.DELIVERY_UNCERTAIN));
            }
            else {
                usage = usage(reply.path("usage"));
                String reason = reply.path("stopReason").asText("");
                switch (reason) {
                    case "end_turn" -> finish(COMPLETED, "", Optional.empty());
                    case "cancelled" -> finish(CANCELLED, "Antigravity turn cancelled", Optional.empty());
                    default -> finish(FAILED, "Antigravity stopped before completing the turn: " + redactor.redact(reason), Optional.empty());
                }
            }
        }

        private Optional<ProviderUsageSample> usage(JsonNode counts)
        {
            if (!counts.has("inputTokens") || !counts.has("outputTokens")) {
                return Optional.empty();
            }
            long input = tokens(counts.path("inputTokens"));
            long cached = tokens(counts.path("cachedReadTokens"));
            long written = tokens(counts.path("cachedWriteTokens"));
            long output = tokens(counts.path("outputTokens"));
            if (input < 0 || cached < 0 || written < 0 || output < 0 || cached > Long.MAX_VALUE - input || written > Long.MAX_VALUE - input - cached) {
                return Optional.empty();
            }
            // ACP input counts fresh tokens. Its output already includes thoughts.
            return Optional.of(new ProviderUsageSample(Optional.of(id.value()), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                    input + cached + written, cached, output, Optional.empty()));
        }

        private static long tokens(JsonNode count)
        {
            if (count.isMissingNode()) {
                return 0;
            }
            return count.isIntegralNumber() && count.canConvertToLong() ? count.longValue() : -1;
        }

        @Override
        public synchronized void cancel()
        {
            if (!terminal) {
                stopTurn(CANCELLED, "Antigravity turn cancelled");
            }
        }

        private void stopTurn(AgentTurnResult.State state, String reason)
        {
            terminal = true;
            child.notify("session/cancel", sessionParams()).orTimeout(1, TimeUnit.SECONDS)
                    .whenComplete((_, _) -> finish(state, reason, Optional.empty()));
        }

        private void finish(AgentTurnResult.State state, String reason, Optional<AgentTurnFailureKind> kind)
        {
            terminal = true;
            String response = answer.append(held).toString();
            // Output callbacks cannot wait for their own process to drain. Complete only after the supervisor checks its tree.
            Thread.startVirtualThread(() -> {
                boolean stopped = false;
                AgentTurnResult outcome;
                try {
                    try {
                        if (state == COMPLETED) {
                            child.endSession(reference.providerValue(), canCloseSession);
                            saved = true;
                        }
                    }
                    finally {
                        child.stop();
                        stopped = true;
                        cleaned.complete(null);
                    }
                    child.awaitDrained();
                    usage.ifPresent(sample -> events.emit(new ProviderAgentEvent.Usage(sample, source)));
                    Optional<String> finalText = Optional.of(response).filter(text -> !text.isBlank() && state == COMPLETED);
                    if (finalText.isPresent()) {
                        events.emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.FINAL, response, source));
                    }
                    outcome = new AgentTurnResult(state, finalText, Optional.of(reason).filter(text -> !text.isEmpty()), elapsed(), true, kind);
                }
                catch (RuntimeException failure) {
                    cleaned.completeExceptionally(failure);
                    outcome = new AgentTurnResult(INTERRUPTED, Optional.empty(),
                            Optional.of(stopped ? "Unable to finish the Antigravity session" : "Antigravity process tree could not be verified stopped"), elapsed(), stopped);
                }
                synchronized (AntigravitySession.this) {
                    if (stopped) {
                        client = null;
                    }
                    else {
                        closed = true;
                    }
                    turn = null;
                }
                completion.complete(outcome);
            });
        }

        private Duration elapsed()
        {
            return Duration.ofNanos(System.nanoTime() - started);
        }
    }
}

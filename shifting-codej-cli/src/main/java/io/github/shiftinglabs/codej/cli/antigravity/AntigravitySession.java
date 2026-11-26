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

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.ProviderInstructions;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.AntigravityMetadata;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ClientCapabilities;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ClientInfo;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ConfigOption;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ElicitationCapability;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ElicitationRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.FormCapability;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HostReply;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HostRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HttpHeader;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HttpMcpServer;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Initialize;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Initialized;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.MessageChunk;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Metadata;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Mode;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ModelOption;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Models;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Prompt;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PromptResult;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SessionDetails;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SessionId;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SessionNotification;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SessionSetup;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SessionUpdate;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SetConfig;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SetMode;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SetModel;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SystemPrompt;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Text;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ToolActivity;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ToolStarted;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Usage;
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
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
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderTurnRef;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.mcp.McpRegistration;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpClient.await;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.INITIALIZE;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.NEW_SESSION;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PROMPT;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.RESUME_SESSION;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SET_CONFIG;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SET_MODE;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SET_MODEL;
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
    private final PermissionBroker permissions;
    private AgentSessionContext context;
    private AgentEventSink events;
    private final Duration startupTimeout;
    private final Consumer<String> recordNewSession;
    private final Runnable onClose;
    private SecretRedactor redactor;
    private Path checkout;
    private volatile AgentSessionRef reference;
    private volatile AcpTurn turn;
    private volatile boolean saved;
    private AcpClient client;
    private Optional<McpRegistration> registration = Optional.empty();
    private List<ConfigOption> configOptions;
    private List<ModelOption> models;
    private boolean canCloseSession;
    private boolean closed;
    private boolean released;

    private AntigravitySession(ProcessSupervisor supervisor, Supplier<ProcessSpec> launch, PermissionBroker permissions, AgentSessionContext context, AgentEventSink events,
            Duration startupTimeout, Consumer<String> recordNewSession, Runnable onClose)
    {
        this.supervisor = supervisor;
        this.launch = launch;
        this.permissions = permissions;
        this.context = context;
        this.events = events;
        this.startupTimeout = startupTimeout;
        this.recordNewSession = recordNewSession;
        this.onClose = onClose;
        this.reference = context.sessionReference().orElse(null);
    }

    static AntigravitySession open(ProcessSupervisor supervisor, Supplier<ProcessSpec> launch, PermissionBroker permissions, AgentSessionContext context, AgentEventSink events,
            Duration startupTimeout, Consumer<String> recordNewSession, Runnable onClose)
    {
        AntigravitySession session = new AntigravitySession(supervisor, launch, permissions, context, events, startupTimeout, recordNewSession, onClose);
        session.connect();
        return session;
    }

    private void connect()
    {
        ProcessSpec spec = launch.get();
        checkout = spec.workingDirectory();
        redactor = SecretRedactor.fromEnvironment(spec.environment());
        if (!context.sessionTools().isEmpty()) {
            registration = permissions.registerSessionTools(context.taskId(), checkout, context.scope(), context.approvals(), redactor, context.sessionTools());
            redactor = redactor.withSecret(registration.orElseThrow().token());
        }
        try {
            client = new AcpClient(supervisor, spec, redactor, this::update, this::request);
            Optional<ElicitationCapability> elicitation = context.asksUser() ? Optional.of(new ElicitationCapability(new FormCapability())) : Optional.empty();
            Initialize initialize = new Initialize(1, new ClientCapabilities(elicitation), new ClientInfo("shifting-codej", "1"));
            Initialized hello = await(client.request(INITIALIZE, initialize), startupTimeout);
            if (hello.protocolVersion() != 1) {
                throw new AgentUnavailableException("Antigravity does not support ACP version 1");
            }
            AgentCapabilities capabilities = hello.agentCapabilities().orElseGet(() -> new AgentCapabilities(Optional.empty(), Optional.empty(), Optional.empty()));
            canCloseSession = capabilities.supportsClose();
            String instructions = ProviderInstructions.compose(context.scope(), "", context.managedInstructions());
            Optional<Metadata<String>> metadata = Optional.empty();
            if (!instructions.isEmpty()) {
                if (!capabilities.supportsInstructions()) {
                    throw new AgentUnavailableException("Antigravity does not support managed ACP system instructions");
                }
                var prompt = new SystemPrompt<>(Optional.of(instructions));
                var extension = new AntigravityMetadata<>(Optional.of(prompt));
                metadata = Optional.of(new Metadata<>(Optional.of(extension)));
            }
            List<HttpMcpServer> servers = ImmutableList.of();
            if (registration.isPresent()) {
                if (!capabilities.supportsHttpTools()) {
                    throw new AgentUnavailableException("Antigravity does not support ACP HTTP host tools");
                }
                McpRegistration tools = registration.orElseThrow();
                var authorization = new HttpHeader("Authorization", "Bearer " + tools.token());
                servers = ImmutableList.of(new HttpMcpServer(AntigravityPolicy.TOOL_SERVER, tools.endpoint(), ImmutableList.of(authorization)));
            }
            SessionDetails setup;
            boolean resume = reference != null;
            SessionSetup params = new SessionSetup(checkout.toString(), Optional.ofNullable(reference).map(AgentSessionRef::providerValue), servers, metadata);
            if (reference == null) {
                setup = await(client.request(NEW_SESSION, params), startupTimeout);
                String id = setup.sessionId().filter(value -> !value.isBlank())
                        .orElseThrow(() -> new AgentUnavailableException("Antigravity returned an empty session ID"));
                reference = new AgentSessionRef(AntigravityCliDriver.LANE, 1, id);
            }
            else {
                if (!capabilities.supportsResume()) {
                    throw new AgentUnavailableException("Antigravity does not support ACP session resume");
                }
                setup = await(client.request(RESUME_SESSION, params), startupTimeout);
            }
            if (!context.restricted()) {
                Mode mode = context.scope().approveEveryEdit() ? Mode.REVIEW_EDITS : Mode.ACCEPT_EDITS;
                await(client.request(SET_MODE, new SetMode(reference.providerValue(), mode)), startupTimeout);
            }
            if (resume) {
                saved = true;
            }
            else {
                recordNewSession.accept(reference.providerValue());
            }
            configOptions = setup.configOptions();
            models = setup.models().map(Models::availableModels).orElseGet(ImmutableList::of);
        }
        catch (RuntimeException failure) {
            registration.ifPresent(McpRegistration::close);
            registration = Optional.empty();
            try {
                if (client != null) {
                    client.stop();
                }
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
        Prompt prompt = new Prompt(reference.providerValue(), ImmutableList.of(new Text(command.message())));
        AcpTurn next = new AcpTurn(new ProviderTurnRef(command.turnId()), client);
        events.emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.TURN_STARTED, "Antigravity turn started"));
        turn = next;
        CompletableFuture<PromptResult> reply;
        try {
            reply = client.request(PROMPT, prompt);
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

    private void selectModel(String value)
    {
        // agy models lists full slugs (including effort). Its ACP config option instead takes the base model.
        for (ModelOption model : models) {
            if (value.equals(model.modelId())) {
                await(client.request(SET_MODEL, new SetModel(reference.providerValue(), value)), startupTimeout);
                return;
            }
        }
        configure("model", value);
    }

    private void configure(String category, String value)
    {
        for (ConfigOption option : configOptions) {
            if (category.equals(option.id()) || option.category().filter(category::equals).isPresent()) {
                configOptions = await(client.request(SET_CONFIG, new SetConfig(reference.providerValue(), option.id(), value)), startupTimeout).configOptions();
                return;
            }
        }
        throw new AgentUnavailableException("Antigravity did not offer the requested " + category + " setting");
    }

    private void update(AcpClient sender, SessionNotification notification)
    {
        AcpTurn current = turn;
        if (current != null && current.child == sender && reference.providerValue().equals(notification.sessionId())) {
            current.update(notification.update());
        }
    }

    private CompletableFuture<HostReply> request(AcpClient sender, HostRequest request)
    {
        AcpTurn current = turn;
        if (current == null || current.child != sender || !reference.providerValue().equals(request.params().sessionId())) {
            return CompletableFuture.completedFuture(AcpInteractions.denied(request));
        }
        return current.request(request);
    }

    @Override
    public void close()
    {
        AcpClient child;
        AcpTurn current;
        Optional<McpRegistration> tools;
        synchronized (this) {
            closed = true;
            child = client;
            current = turn;
            tools = registration;
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
        try {
            if (child != null) {
                child.stop();
                if (current == null) {
                    child.awaitDrained();
                }
            }
        }
        finally {
            if (!stopTools(tools)) {
                throw new AgentUnavailableException("Antigravity host tool callbacks could not be verified stopped");
            }
        }
        synchronized (this) {
            if (!released) {
                released = true;
                onClose.run();
            }
        }
    }

    private static boolean stopTools(Optional<McpRegistration> registration)
    {
        if (registration.isEmpty()) {
            return true;
        }
        try (McpRegistration tools = registration.orElseThrow()) {
            return tools.stopToolCallbacks(Duration.ofSeconds(2));
        }
    }

    private final class AcpTurn
            implements RunningTurn
    {
        private final ProviderTurnRef id;
        private final AcpClient child;
        private final Optional<McpRegistration> registration = AntigravitySession.this.registration;
        private final ProviderAgentEventSource source;
        private final long started = System.nanoTime();
        private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> cleaned = new CompletableFuture<>();
        private final StringBuilder answer = new StringBuilder();
        private final Map<String, String> tools = new HashMap<>();
        private final Set<CompletableFuture<ProviderApprovalDecision>> approvals = new HashSet<>();
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

        private void update(SessionUpdate update)
        {
            ProviderAgentEvent event = mapUpdate(update);
            if (event != null) {
                events.emit(event);
            }
        }

        private CompletableFuture<HostReply> request(HostRequest request)
        {
            return switch (request) {
                case ElicitationRequest asked when context.asksUser() -> AcpInteractions.question(asked.params(), source, redactor, this::ask);
                case PermissionRequest permission when !context.restricted() -> AcpInteractions.edit(permission.params(), checkout, source, redactor, this::ask);
                default -> CompletableFuture.completedFuture(AcpInteractions.denied(request));
            };
        }

        private synchronized CompletableFuture<ProviderApprovalDecision> ask(ProviderApprovalRequest request)
        {
            if (terminal || approvals.size() >= 16) {
                return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
            }
            CompletableFuture<ProviderApprovalDecision> answer = new CompletableFuture<>();
            approvals.add(answer);
            answer.whenComplete((_, _) -> {
                synchronized (this) {
                    approvals.remove(answer);
                }
            });
            AgentApprovalHandler handler = context.approvals();
            Thread.startVirtualThread(() -> {
                if (answer.isDone()) {
                    return;
                }
                try {
                    CompletableFuture<ProviderApprovalDecision> decision = handler.request(request, AgentApprovalHandler.WAIT).toCompletableFuture();
                    answer.whenComplete((_, _) -> decision.cancel(false));
                    decision.whenComplete((value, failure) -> answer.complete(value != null && failure == null ? value : ProviderApprovalDecision.deny()));
                }
                catch (RuntimeException failure) {
                    answer.complete(ProviderApprovalDecision.deny());
                }
            });
            return answer.completeOnTimeout(ProviderApprovalDecision.expired(), AgentApprovalHandler.WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized ProviderAgentEvent mapUpdate(SessionUpdate update)
        {
            if (terminal) {
                return null;
            }
            return switch (update) {
                case MessageChunk chunk when chunk.content() instanceof Text text -> message(text.text());
                case ToolActivity activity -> toolActivity(activity);
                case null, default -> null;
            };
        }

        private ProviderAgentEvent message(String text)
        {
            SecretRedactor.Chunk chunk = redactor.redactChunk(held, text);
            if (chunk.text().length() + chunk.held().length() > MAX_ANSWER_LENGTH - answer.length()) {
                throw new AgentUnavailableException("Antigravity response exceeds the output limit");
            }
            held = chunk.held();
            answer.append(chunk.text());
            return chunk.text().isEmpty() ? null : new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.PARTIAL, chunk.text(), source);
        }

        private ProviderAgentEvent toolActivity(ToolActivity activity)
        {
            String item = activity.toolCallId();
            if (item.isBlank() || item.length() > 1024) {
                return null;
            }
            String title = Ascii.truncate(redactor.redact(activity.title().orElseGet(() -> tools.getOrDefault(item, "Tool"))), 1024, "...");
            if (title.isBlank()) {
                return null;
            }
            ProviderAgentEvent.ActivityState state = activity.status().map(status -> switch (status) {
                case COMPLETED -> ProviderAgentEvent.ActivityState.COMPLETED;
                case FAILED -> ProviderAgentEvent.ActivityState.FAILED;
                case PENDING -> ProviderAgentEvent.ActivityState.STARTED;
                }).orElseGet(() -> activity instanceof ToolStarted ? ProviderAgentEvent.ActivityState.STARTED : ProviderAgentEvent.ActivityState.PROGRESS);
            if (state == ProviderAgentEvent.ActivityState.COMPLETED || state == ProviderAgentEvent.ActivityState.FAILED) {
                tools.remove(item);
            }
            else if (tools.size() < 1024) {
                tools.put(item, title);
            }
            return new ProviderAgentEvent.Tool(Optional.empty(), title, state, title, source.withItem(Optional.of(redactor.redact(item))));
        }

        private synchronized void result(PromptResult reply, Throwable failure)
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
                usage = reply.usage().flatMap(this::usage);
                String reason = reply.stopReason().orElse("");
                switch (reason) {
                    case "end_turn" -> finish(COMPLETED, "", Optional.empty());
                    case "cancelled" -> finish(CANCELLED, "Antigravity turn cancelled", Optional.empty());
                    default -> finish(FAILED, "Antigravity stopped before completing the turn: " + redactor.redact(reason), Optional.empty());
                }
            }
        }

        private Optional<ProviderUsageSample> usage(Usage counts)
        {
            if (counts.inputTokens().isEmpty() || counts.outputTokens().isEmpty()) {
                return Optional.empty();
            }
            long input = counts.inputTokens().orElseThrow();
            long cached = counts.cachedReadTokens().orElse(0L);
            long written = counts.cachedWriteTokens().orElse(0L);
            long output = counts.outputTokens().orElseThrow();
            if (input < 0 || cached < 0 || written < 0 || output < 0 || cached > Long.MAX_VALUE - input || written > Long.MAX_VALUE - input - cached) {
                return Optional.empty();
            }
            // ACP input counts fresh tokens. Its output already includes thoughts.
            return Optional.of(new ProviderUsageSample(Optional.of(id.value()), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                    input + cached + written, cached, output, Optional.empty()));
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
            withdrawApprovals();
            child.cancelSession(new SessionId(reference.providerValue())).orTimeout(1, TimeUnit.SECONDS)
                    .whenComplete((_, _) -> finish(state, reason, Optional.empty()));
        }

        private void finish(AgentTurnResult.State state, String reason, Optional<AgentTurnFailureKind> kind)
        {
            terminal = true;
            withdrawApprovals();
            registration.ifPresent(tools -> tools.stopToolCallbacks(Duration.ZERO));
            String response = answer.append(held).toString();
            // Output callbacks cannot wait for their own process to drain. Complete only after the supervisor checks its tree.
            Thread.startVirtualThread(() -> {
                boolean stopped = false;
                boolean toolsStopped = false;
                AgentTurnResult outcome;
                try {
                    try {
                        if (state == COMPLETED) {
                            child.endSession(reference.providerValue(), canCloseSession);
                            saved = true;
                        }
                    }
                    finally {
                        try {
                            child.stop();
                            stopped = true;
                        }
                        finally {
                            toolsStopped = stopTools(registration);
                        }
                        if (!toolsStopped) {
                            throw new AgentUnavailableException("Antigravity host tool callbacks could not be verified stopped");
                        }
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
                    String diagnostic = "Unable to finish the Antigravity session";
                    if (!stopped) {
                        diagnostic = "Antigravity process tree could not be verified stopped";
                    }
                    else if (!toolsStopped) {
                        diagnostic = "Antigravity host tool callbacks could not be verified stopped";
                    }
                    outcome = new AgentTurnResult(INTERRUPTED, Optional.empty(),
                            Optional.of(diagnostic), elapsed(), stopped && toolsStopped);
                }
                synchronized (AntigravitySession.this) {
                    if (stopped && toolsStopped) {
                        client = null;
                        AntigravitySession.this.registration = Optional.empty();
                    }
                    else {
                        closed = true;
                    }
                    turn = null;
                }
                completion.complete(outcome);
            });
        }

        private void withdrawApprovals()
        {
            ImmutableList.copyOf(approvals).forEach(answer -> answer.complete(ProviderApprovalDecision.deny()));
        }

        private Duration elapsed()
        {
            return Duration.ofNanos(System.nanoTime() - started);
        }
    }
}

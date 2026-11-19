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
import io.github.shiftinglabs.codej.cli.api.tools.BuiltinTools;
import io.github.shiftinglabs.codej.cli.api.tools.CodingToolException;
import io.github.shiftinglabs.codej.cli.api.tools.LegacyToolAdapter;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionContext;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptSessionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.shiftinglabs.codej.cli.api.ApiEngine.CALLBACK;
import static java.util.Objects.requireNonNull;

final class ApiSession
        implements AgentSession
{
    final ApiEngine engine;
    private final ApiEngineConfig config;
    final AgentSessionContext context;
    final AgentEventSink events;
    final ApiSessionRepository store;
    final AgentSessionRef reference;
    ApiToolRegistry tools = new ApiToolRegistry(List.of());
    ToolDispatcher dispatcher;
    Optional<BuiltinTools> builtins = Optional.empty();
    private final SessionToolSets toolSets = new SessionToolSets();
    final SessionPromptSources prompts = new SessionPromptSources();
    final SessionExtensions extensions = new SessionExtensions();
    final ToolRequirements grants;
    private ApiTurn running;
    private boolean closing;
    private boolean released;
    private boolean initializing = true;
    private boolean releasing;
    volatile boolean damaged;

    ApiSession(ApiEngine engine, AgentSessionContext context, AgentEventSink events, ApiSessionRepository store, AgentSessionRef reference)
    {
        this.engine = engine;
        this.config = engine.config;
        dispatcher = new ToolDispatcher(tools, config.shutdownTimeout, config.toolExecutionBudget);
        this.context = context;
        this.events = events;
        this.store = store;
        this.reference = reference;
        var authority = ToolRequirements.builder();
        if (!context.textOnly()) {
            authority.require(ToolCapability.HOST_INVOKE);
            if (context.access() == SessionAccess.FULL || context.access() == SessionAccess.READ_ONLY) {
                authority.read(Path.of(".")).require(ToolCapability.PROCESS_EXECUTE);
                if (context.access() == SessionAccess.FULL) {
                    authority.write(Path.of("."));
                }
            }
        }
        grants = authority.build();
    }

    private ToolRegistration legacy(Tool tool, boolean builtin)
    {
        LegacyToolAdapter.Builder adapter = LegacyToolAdapter.builder(tool).failures(failure -> legacyFailure(failure, builtin));
        if (builtin) {
            BuiltinTools owner = builtins.orElseThrow();
            adapter.stopVerifier(owner::stopAndVerify).committed(owner::committed);
        }
        else {
            config.toolExecutor.ifPresent(adapter::executor);
        }
        return ToolRegistration.create(adapter.build(), true, config.approveTools || builtins.isPresent(), Optional.of(tool),
                failure -> failure.code().equals("TOOL_FAILED") ? failure.message() : BuiltinTools.failure(failure.code(), failure.message()));
    }

    private ToolOutcome<String> legacyFailure(Throwable failure, boolean builtin)
    {
        if (failure instanceof CodingToolException codingFailure) {
            ToolFailure value = new ToolFailure(codingFailure.failure().code(), codingFailure.failure().message());
            return codingFailure.uncertain() ? ToolOutcome.uncertain(value) : ToolOutcome.failed(value);
        }
        return ToolOutcome.failed(builtin ? new ToolFailure("INVALID_ARGUMENTS", "Invalid built-in tool arguments or operation")
                : new ToolFailure("TOOL_FAILED", "Host tool failed"));
    }

    void initialize(ToolProviderPlan plan)
    {
        try {
            ApiCheckpoint before = store.state();
            boolean resources = engine.coding(context) || !plan.families().isEmpty() || !config.promptProviders.isEmpty() || !config.extensions.isEmpty();
            if (resources) {
                store.save(before.withPhase(ApiCheckpoint.Phase.BLOCKED));
            }
            if (engine.coding(context)) {
                builtins = Optional.of(new BuiltinTools(context.worktree(), config.codingTools.orElseThrow(),
                        context.access() == SessionAccess.FULL, List.of(engine.profile, context.runtimeDirectory()), engine.supervisor, reference.providerValue(),
                        config.maximumToolOutputChars, launch -> {
                            store.save(store.state().append(new ApiJournal.CodingLaunch(launch)));
                        }));
            }
            List<ToolRegistration> registrations = new ArrayList<>();
            context.sessionTools().forEach(tool -> registrations.add(legacy(tool, false)));
            builtins.ifPresent(value -> value.tools().forEach(tool -> registrations.add(legacy(tool, true))));
            registrations.addAll(toolSets.open(plan, assembly()));
            tools = ApiToolRegistry.registered(registrations);
            dispatcher = new ToolDispatcher(tools, config.shutdownTimeout, config.toolExecutionBudget);
            ExtensionScope scope = new ExtensionScope();
            prompts.open(config.promptProviders, scope);
            extensions.open(config.extensions, scope);
            engine.requireOpen();
            if (builtins.isPresent() && before.recoverable()) {
                builtins.orElseThrow().initialize();
            }
            if (resources) {
                store.save(before);
            }
            builtins.ifPresent(BuiltinTools::committed);
        }
        catch (IOException | RuntimeException failure) {
            throw new AgentUnavailableException("API tool resources could not be initialized; required backends must enforce containment");
        }
        finally {
            synchronized (this) {
                initializing = false;
            }
        }
    }

    private final class ExtensionScope
            implements PromptSessionContext, EngineExtensionContext
    {
        @Override
        public AgentSessionRef reference() { return reference; }

        @Override
        public ConnectorIdentity identity() { return engine.identity(); }

        @Override
        public Path worktree() { return context.worktree(); }

        @Override
        public SessionAccess access() { return context.access(); }

        @Override
        public Duration shutdownTimeout() { return config.shutdownTimeout; }
    }

    private ToolSetContext assembly()
    {
        Executor executor = config.toolExecutor.orElse(Runnable::run);
        return new ToolSetContext()
        {
            @Override
            public AgentSessionContext session() { return context; }

            @Override
            public ConnectorContext connector() { return engine.host; }

            @Override
            public Executor executor() { return executor; }

            @Override
            public Duration shutdownTimeout() { return config.shutdownTimeout; }

            @Override
            public Optional<ToolExecutionBudget> toolExecutionBudget() { return config.toolExecutionBudget; }
        };
    }

    @Override
    public AgentSessionRef reference() { return reference; }

    @Override
    public synchronized boolean resumable() { return !damaged && store.state().recoverable(); }

    @Override
    public synchronized RunningTurn start(AgentTurnCommand command)
    {
        engine.requireOpen();
        requireNonNull(command, "command is null");
        if (closing || running != null) {
            throw new AgentUnavailableException("API session is closed or already has an active turn");
        }
        ApiCheckpoint before = store.state();
        if (damaged || !before.recoverable()) {
            throw new AgentUnavailableException("Previous API request or tool outcome is uncertain; reconciliation is required");
        }
        builtins.ifPresent(BuiltinTools::committed);
        dispatcher.committed();
        String model = command.model().or(context::model).orElse(config.model);
        Optional<String> effort = command.effort().or(context::effort);
        ModelCapabilities supported = engine.options(model, effort, command.serviceTier(), !tools.definitions().isEmpty());
        if (command.message().length() > config.maximumTextChars
                || command.attachments().stream().anyMatch(item -> !supported.attachmentMediaTypes().contains(item.mediaType()))) {
            throw new AgentUnavailableException("API turn input exceeds its limits or uses unsupported attachments");
        }
        ModelInput.UserMessage input = store.stage(command.message(), command.attachments());
        if (before.phase() == ApiCheckpoint.Phase.TOOLS && !tools.matches(before.setup().orElseThrow())) {
            throw new AgentUnavailableException("Pending API tool batch requires its original tool definitions");
        }
        store.save(before.append(new ApiJournal.User(command.turnId(), input)));
        ApiTurn turn = new ApiTurn(this, command, input, model, effort);
        running = turn;
        try {
            if (!command.maximumRuntime().isZero()) {
                turn.timeout = engine.timer.schedule(() -> turn.stop("API turn deadline exceeded"), command.maximumRuntime().toMillis(), TimeUnit.MILLISECONDS);
            }
            engine.workers.execute(turn::run);
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

    synchronized void finished()
    {
        running = null;
        if (closing) {
            release();
        }
    }

    private synchronized void release()
    {
        if (!released) {
            if (initializing || releasing) {
                throw new AgentUnavailableException("API session resource release cannot be confirmed during initialization or recursive cleanup");
            }
            releasing = true;
            try {
                dispatcher.verifyStopped();
                extensions.close();
                prompts.close();
                toolSets.close();
                if (builtins.isPresent()) {
                    builtins.orElseThrow().close();
                }
                dispatcher.resourcesClosed();
                store.close();
                released = true;
                engine.released(this);
            }
            catch (IOException | RuntimeException failure) {
                throw new AgentUnavailableException("Coding tool resources could not be released; workspace ownership is retained");
            }
            finally {
                releasing = false;
            }
        }
    }

    synchronized boolean onWorker() { return running != null && running.runner == Thread.currentThread(); }

    void requestClose()
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

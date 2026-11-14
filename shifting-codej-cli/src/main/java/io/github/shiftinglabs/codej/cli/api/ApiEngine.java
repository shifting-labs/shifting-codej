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
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Shared direct-API runtime. Owns its model client and default bounded workers; borrows all
 * ConnectorContext services. Coding tools are opt-in and contained separately from trusted host callbacks.
 * No provider CLI or subagent is started.
 */
final class ApiEngine
        implements ConnectorDriver
{
    static final ThreadLocal<ApiSession> CALLBACK = new ThreadLocal<>();
    final ApiEngineConfig config;
    final Path profile;
    final ConnectorContext host;
    final ProcessSupervisor supervisor;
    final ModelClient client;
    final ExecutorService workers;
    final ScheduledExecutorService timer;
    private final Set<ApiSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    final ApiSessionRepository.Factory repositories;
    final ApiPromptAssembler prompts;

    ApiEngine(ApiEngineConfig config, ConnectorContext context, ApiSessionRepository.Factory repositories, ApiPromptAssembler prompts)
    {
        this.config = requireNonNull(config, "config is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.prompts = requireNonNull(prompts, "prompts is null");
        host = requireNonNull(context, "context is null");
        profile = host.profileDirectory();
        supervisor = context.processSupervisor();
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
    public void validate(AgentSessionContext context) { plan(context); }

    private ToolProviderPlan plan(AgentSessionContext context)
    {
        requireOpen();
        requireNonNull(context, "context is null");
        var scope = context.scope();
        boolean coding = coding(context);
        boolean workspace = coding || (!config.toolProviders.isEmpty() && (context.access() == SessionAccess.FULL || context.access() == SessionAccess.READ_ONLY));
        if ((!workspace && context.access() != SessionAccess.TEXT_ONLY && context.access() != SessionAccess.TOOLS_ONLY) || scope.policy() != AgentPermissionPolicy.AUTO
                || context.review() || context.publicationProtected() || context.asksUser() || scope.readOnlySubagents() || (!workspace && scope.fixedSandbox())
                || (workspace && scope.network()) || scope.webSearch() || scope.commits() || (!workspace && (scope.approveEveryCommand() || scope.approveEveryEdit()))
                || !scope.writableRoots().isEmpty()
                || !scope.unsandboxedCommands().isEmpty() || !scope.unixSockets().isEmpty() || !scope.reviewerContext().isEmpty()
                || !scope.reviewerRules().isEmpty() || scope.reviewSandboxedCommands()
                || !scope.sessionDeniedCommands().isEmpty() || !scope.sessionDeniedReads().isEmpty()) {
            throw new AgentUnavailableException("Requested API engine access or constraints are unsupported; coding tools require explicit configuration");
        }
        List<Tool> definitions = new ArrayList<>(context.sessionTools());
        if (coding) {
            if (context.sessionTools().stream().anyMatch(tool -> BuiltinTools.contains(tool.name())) || config.maximumToolOutputChars < 512) {
                throw new AgentUnavailableException("Built-in tool names are reserved and require at least 512 output characters");
            }
            definitions.addAll(BuiltinTools.definitions(context.access() == SessionAccess.FULL));
        }
        ToolProviderPlan plan = ToolProviderPlan.create(context.textOnly() ? List.of() : config.toolProviders, definitions);
        if (coding && plan.families().stream().flatMap(family -> family.catalog().stream()).anyMatch(entry -> BuiltinTools.contains(entry.wire().name()))) {
            throw new AgentUnavailableException("Built-in tool names are reserved");
        }
        options(context.model().orElse(config.model), context.effort(), Optional.empty(), plan.hasTools());
        context.sessionReference().ifPresent(reference -> {
            if (!reference.laneId().equals(id()) || reference.contractVersion() != 1 || !canonicalId(reference.providerValue())) {
                throw new AgentUnavailableException("API session reference belongs to another lane or version");
            }
        });
        return plan;
    }

    boolean coding(AgentSessionContext context)
    {
        return config.codingTools.isPresent() && (context.access() == SessionAccess.READ_ONLY || context.access() == SessionAccess.FULL);
    }

    ModelCapabilities options(String model, Optional<String> effort, Optional<String> tier, boolean tools)
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
        ToolProviderPlan plan = plan(context);
        requireNonNull(events, "events is null");
        if (sessions.size() >= config.maximumSessions) {
            throw new AgentUnavailableException("API session capacity reached");
        }
        String id = context.sessionReference().map(AgentSessionRef::providerValue).orElseGet(() -> UUID.randomUUID().toString());
        ApiSessionRepository store = requireNonNull(repositories.open(profile, id, context.sessionReference().isPresent(), config), "repository factory returned null");
        ApiSession session;
        try {
            session = new ApiSession(this, context, events, store, new AgentSessionRef(id(), 1, id));
        }
        catch (RuntimeException | Error failure) {
            store.close();
            throw failure;
        }
        sessions.add(session);
        try {
            session.initialize(plan);
            return session;
        }
        catch (RuntimeException | Error failure) {
            session.damaged = true;
            try {
                session.close();
            }
            catch (RuntimeException | Error stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            throw failure;
        }
    }

    void requireOpen()
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

    void released(ApiSession session) { sessions.remove(session); }
}

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
package io.github.shiftinglabs.codej.sdk.impl;

import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Owns Codex sessions and its broker; the host's supervisor and retention sweep remain host-owned. */
public final class CodexConnectorDriver
        implements ConnectorDriver
{
    private final AgentDriver delegate;
    private final PermissionBroker broker;
    private final ConnectorIdentity identity;
    private final Optional<String> unavailable;
    private final Optional<String> testedVersion;
    private final Set<OwnedSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private boolean released;
    private int active;

    CodexConnectorDriver(AgentDriver delegate, PermissionBroker broker, ConnectorIdentity identity, Optional<String> unavailable, Optional<String> testedVersion)
    {
        this.delegate = requireNonNull(delegate, "delegate is null");
        this.broker = requireNonNull(broker, "broker is null");
        this.identity = requireNonNull(identity, "identity is null");
        this.unavailable = requireNonNull(unavailable, "unavailable is null");
        this.testedVersion = requireNonNull(testedVersion, "testedVersion is null");
    }

    /** The original install failure, which the SDK presents without attempting the fallback executable. */
    public Optional<String> unavailable()
    {
        return unavailable;
    }

    /** Only caller-selected executables need the SDK's newer-than-tested diagnostic. */
    public Optional<String> testedVersion()
    {
        return testedVersion;
    }

    @Override
    public ConnectorIdentity identity() { return identity; }

    @Override
    public AgentLaneId id() { return delegate.id(); }

    @Override
    public String label() { return delegate.label(); }

    @Override
    public AgentCapabilities capabilities() { return delegate.capabilities(); }

    @Override
    public boolean reviewChildAgents() { return delegate.reviewChildAgents(); }

    @Override
    public boolean reviewChildAgents(Optional<AgentSessionRef> reference, Optional<String> model) { return delegate.reviewChildAgents(reference, model); }

    @Override
    public AgentProviderStatus probe()
    {
        return call(() -> unavailable.map(message ->
                        new AgentProviderStatus(id(), AgentProviderStatus.Readiness.CLI_MISSING, Optional.empty(), message, capabilities()))
                .orElseGet(delegate::probe));
    }

    @Override
    public AgentProviderStatus probe(Optional<String> model)
    {
        requireNonNull(model, "model is null");
        return unavailable.isPresent() ? probe() : call(() -> delegate.probe(model));
    }

    @Override
    public ProviderQuotaSnapshot quota()
    {
        return call(() -> unavailable.map(ProviderQuotaSnapshot::unavailable).orElseGet(delegate::quota));
    }

    @Override
    public List<ProviderModel> models()
    {
        requireAvailable();
        return call(delegate::models);
    }

    @Override
    public void validate(AgentSessionContext context)
    {
        requireNonNull(context, "context is null");
        requireAvailable();
        context.sessionReference().ifPresent(reference -> {
            if (reference.contractVersion() == 2) {
                throw new AgentUnavailableException("This Codex conversation has an outdated tool list. Start a new task to use live tool discovery");
            }
            if (!reference.laneId().equals(id()) || (reference.contractVersion() != 1 && reference.contractVersion() != 3)) {
                throw new AgentUnavailableException("Unsupported Codex session reference");
            }
        });
        // These are the existing launch-profile checks, before readiness, catalog or MCP side effects.
        boolean shellOff = context.access() == SessionAccess.TEXT_ONLY || context.access() == SessionAccess.TOOLS_ONLY;
        checkArgument(!context.scope().network() || !shellOff, "A text-only Codex profile has no network");
        checkArgument(!context.scope().webSearch() || !shellOff, "A text-only Codex profile has no web search");
        checkArgument(!context.scope().readOnlySubagents() || (!shellOff && !context.scope().fixedSandbox()),
                "A text-only or fixed Codex profile cannot gate read-only subagents");
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        requireNonNull(events, "events is null");
        validate(context);
        return call(() -> {
            OwnedSession session = new OwnedSession(delegate.open(context, events));
            sessions.add(session);
            requireOpen();
            return session;
        });
    }

    /** Never hold the lifecycle lock across provider calls: their event callbacks may call back into this connector. */
    private <T> T call(Supplier<T> operation)
    {
        synchronized (this) {
            requireOpen();
            active++;
        }
        Optional<Throwable> failure = Optional.empty();
        try {
            return operation.get();
        }
        catch (RuntimeException | Error providerFailure) {
            failure = Optional.of(providerFailure);
            throw providerFailure;
        }
        finally {
            boolean closing;
            synchronized (this) {
                active--;
                closing = closed;
            }
            if (closing) {
                try {
                    close();
                }
                catch (RuntimeException | Error closingFailure) {
                    if (failure.isEmpty()) {
                        throw closingFailure;
                    }
                    if (failure.orElseThrow() != closingFailure) {
                        failure.orElseThrow().addSuppressed(closingFailure);
                    }
                }
            }
        }
    }

    @Override
    public void close()
    {
        synchronized (this) {
            if (released) {
                return;
            }
            closed = true;
        }
        Optional<RuntimeException> failure = Optional.empty();
        for (OwnedSession session : ImmutableList.copyOf(sessions)) {
            try {
                session.close();
            }
            catch (RuntimeException closing) {
                if (failure.isEmpty()) {
                    failure = Optional.of(closing);
                }
                else if (failure.orElseThrow() != closing) {
                    failure.orElseThrow().addSuppressed(closing);
                }
            }
        }
        try {
            broker.close();
        }
        catch (RuntimeException closing) {
            if (failure.isEmpty()) {
                failure = Optional.of(closing);
            }
            else if (failure.orElseThrow() != closing) {
                failure.orElseThrow().addSuppressed(closing);
            }
        }
        failure.ifPresent(closing -> { throw closing; });
        synchronized (this) {
            if (active != 0 || !sessions.isEmpty()) {
                throw new AgentUnavailableException("Codex provider operations are still in progress");
            }
            released = true;
        }
    }

    private void requireOpen()
    {
        if (closed) {
            throw new AgentUnavailableException("Codex connector is closed");
        }
    }

    private void requireAvailable()
    {
        requireOpen();
        unavailable.ifPresent(message -> { throw new AgentUnavailableException(message); });
    }

    private final class OwnedSession
            implements AgentSession
    {
        private final AgentSession session;

        private OwnedSession(AgentSession session) { this.session = requireNonNull(session, "session is null"); }

        @Override
        public AgentSessionRef reference() { return session.reference(); }

        @Override
        public void allowReuse() { session.allowReuse(); }

        @Override
        public void preventReuse() { session.preventReuse(); }

        @Override
        public boolean resumable() { return session.resumable(); }

        @Override
        public boolean retained() { return session.retained(); }

        @Override
        public boolean prepareTurn(AgentSessionContext context, AgentEventSink events)
        {
            requireNonNull(events, "events is null");
            validate(context);
            return sessions.contains(this) && session.prepareTurn(context, events);
        }

        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            requireAvailable();
            if (!sessions.contains(this)) {
                throw new AgentUnavailableException("Codex session is closed");
            }
            return session.start(command);
        }

        @Override
        public void close()
        {
            if (sessions.contains(this)) {
                session.close();
                sessions.remove(this);
            }
        }
    }
}

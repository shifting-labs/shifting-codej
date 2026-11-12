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
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Adds connector validation and resource ownership without changing Claude's session/turn driver. */
final class ClaudeConnectorDriver
        implements ConnectorDriver
{
    private final AgentDriver driver;
    private final ConnectorIdentity identity;
    private final List<AutoCloseable> owned;
    private volatile boolean closed;
    private boolean released;

    ClaudeConnectorDriver(AgentDriver driver, ConnectorIdentity identity, List<AutoCloseable> owned)
    {
        this.driver = requireNonNull(driver, "driver is null");
        this.identity = requireNonNull(identity, "identity is null");
        this.owned = ImmutableList.copyOf(owned);
    }

    @Override
    public ConnectorIdentity identity()
    {
        return identity;
    }

    @Override
    public AgentLaneId id()
    {
        return driver.id();
    }

    @Override
    public String label()
    {
        return driver.label();
    }

    @Override
    public boolean reviewChildAgents()
    {
        return driver.reviewChildAgents();
    }

    @Override
    public boolean reviewChildAgents(Optional<AgentSessionRef> reference, Optional<String> model)
    {
        return driver.reviewChildAgents(reference, model);
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return driver.capabilities();
    }

    @Override
    public AgentProviderStatus probe()
    {
        return driver.probe();
    }

    @Override
    public AgentProviderStatus probe(Optional<String> model)
    {
        return driver.probe(model);
    }

    @Override
    public ProviderQuotaSnapshot quota()
    {
        return driver.quota();
    }

    @Override
    public List<ProviderModel> models()
    {
        return driver.models();
    }

    @Override
    public void validate(AgentSessionContext context)
    {
        requireNonNull(context, "context is null");
        if (closed) {
            throw new AgentUnavailableException("Claude driver is closed");
        }
        // Match ClaudeCliDriver.open: other existing model and permission combinations remain valid.
        context.sessionReference().ifPresent(reference -> {
            if (!reference.laneId().equals(id()) || reference.contractVersion() != 1) {
                throw new AgentUnavailableException("Unsupported Claude session reference");
            }
        });
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        validate(context);
        return driver.open(context, events);
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        if (released) {
            return;
        }
        Optional<AgentException> failure = Optional.empty();
        for (AutoCloseable resource : owned) {
            try {
                resource.close();
            }
            catch (Exception closing) {
                failure = failure.or(() -> Optional.of(DriverSession.agentException(closing)));
            }
        }
        if (failure.isPresent()) {
            // A later close retries a driver whose process could not yet be shown stopped.
            throw failure.orElseThrow();
        }
        released = true;
    }
}

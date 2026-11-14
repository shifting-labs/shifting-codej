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

import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.util.List;
import java.util.Optional;

/** Shared direct-API runtime facade. Existing callers retain the same configuration and driver contract. */
public final class ApiAgentDriver
        implements ConnectorDriver
{
    private final ConnectorDriver engine;

    public ApiAgentDriver(ApiEngineConfig config, ConnectorContext context)
    {
        engine = new ApiEngine(config, context, ApiSessionStore::open, ApiPromptAssembler.DEFAULT);
    }

    @Override
    public ConnectorIdentity identity() { return engine.identity(); }

    @Override
    public AgentLaneId id() { return engine.id(); }

    @Override
    public String label() { return engine.label(); }

    @Override
    public AgentCapabilities capabilities() { return engine.capabilities(); }

    @Override
    public AgentProviderStatus probe() { return engine.probe(); }

    @Override
    public AgentProviderStatus probe(Optional<String> model) { return engine.probe(model); }

    @Override
    public List<ProviderModel> models() { return engine.models(); }

    @Override
    public ProviderQuotaSnapshot quota() { return engine.quota(); }

    @Override
    public void validate(AgentSessionContext context) { engine.validate(context); }

    @Override
    public synchronized AgentSession open(AgentSessionContext context, AgentEventSink events) { return engine.open(context, events); }

    @Override
    public void close() { engine.close(); }
}

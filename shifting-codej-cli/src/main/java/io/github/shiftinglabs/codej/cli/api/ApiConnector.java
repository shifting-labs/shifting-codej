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

import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Provider-bound factory for the shared engine; native API adapters supply the configured client factory. */
public final class ApiConnector
        implements AgentConnector<ApiEngineConfig>
{
    private final String id;

    public ApiConnector(String id)
    {
        this.id = new ConnectorIdentity(id, "validation").connectorId();
    }

    @Override
    public String id() { return id; }

    @Override
    public Class<ApiEngineConfig> configurationType() { return ApiEngineConfig.class; }

    @Override
    public ApiAgentDriver create(ApiEngineConfig configuration, ConnectorContext context)
    {
        requireNonNull(configuration, "configuration is null");
        requireNonNull(context, "context is null");
        checkArgument(id.equals(configuration.identity.connectorId()), "configuration belongs to another connector");
        return new ApiAgentDriver(configuration, context);
    }
}

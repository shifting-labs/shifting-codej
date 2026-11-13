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

import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.cli.api.ApiConnector;
import io.github.shiftinglabs.codej.cli.api.ApiEngineConfig;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;

/** The direct Messages connector: the adapter owns HTTP; the shared engine owns sessions and host tools. */
public final class ClaudeApiConnector
        implements AgentConnector<ClaudeApiConfig>
{
    @Override
    public String id() { return "claude-api"; }

    @Override
    public Class<ClaudeApiConfig> configurationType() { return ClaudeApiConfig.class; }

    @Override
    public ConnectorDriver create(ClaudeApiConfig config, ConnectorContext context)
    {
        requireNonNull(config, "configuration is null");
        requireNonNull(context, "context is null");
        String profile = Hashing.sha256().hashString(context.profileDirectory().toAbsolutePath().normalize() + "\n" + config.baseUrl,
                StandardCharsets.UTF_8).toString().substring(0, 16);
        AnthropicModelClient client = new AnthropicModelClient(config);
        try {
            ApiEngineConfig.Builder engine = ApiEngineConfig.builder(new ConnectorIdentity(id(), "profile-" + profile),
                            new AgentLaneId(id() + "-" + profile), config.model, () -> client)
                    .maximumOutputTokens(config.maximumOutputTokens).redactorSupplier(client::redactor)
                    .toolExecutor(config.callbacks.orElse(command -> Thread.ofVirtual().start(command)));
            config.codingTools.ifPresent(engine::codingTools);
            return new ApiConnector(id()).create(engine.build(), context);
        }
        catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }
}

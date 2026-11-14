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

import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProviderBinding;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestApiConnector;
import io.github.shiftinglabs.codej.sdk.testing.ConfiguredLookupTools;
import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekApiConnector
        extends AbstractTestApiConnector<DeepSeekApiConfig>
{
    private DeepSeekHttpFixture http;

    @Test
    void providerConfigurationBindingsAreExplicitAndSnapshotBuilderState()
    {
        var provider = new ConfiguredLookupTools();
        var binding = ToolProviderBinding.of(provider, new ConfiguredLookupTools.Configuration("configured:"));
        var builder = DeepSeekApiConfig.builder("model", () -> "key").toolProvider(binding);
        var first = builder.build();
        builder.toolProvider(provider, new ConfiguredLookupTools.Configuration("second:"));
        assertThat(first.toolProviders).containsExactly(binding);
        assertThat(builder.build().toolProviders).hasSize(2);
        assertThat(provider.opened).hasValue(0);
        assertThatThrownBy(() -> builder.toolProvider((ToolProviderBinding) null)).isInstanceOf(NullPointerException.class);
    }

    @BeforeEach
    void setup()
            throws Exception
    {
        http = new DeepSeekHttpFixture();
    }

    @AfterEach
    void cleanup() { http.close(); }

    @Override
    protected AgentConnector<DeepSeekApiConfig> connector() { return new DeepSeekApiConnector(); }

    @Override
    protected DeepSeekApiConfig configuration()
    {
        return DeepSeekApiConfig.builder(DeepSeekHttpFixture.MODEL, () -> "sk-test-contract").baseUrl(http.baseUrl()).build();
    }
}

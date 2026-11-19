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
import io.github.shiftinglabs.codej.cli.spi.context.ContextBudget;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestApiConnector;
import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekApiConnector
        extends AbstractTestApiConnector<ApiConfig>
{
    private DeepSeekHttpFixture http;

    @Test
    void defaultsToItsOwnEndpointAndRejectsAnotherProvidersConfiguration()
    {
        var builder = ApiConfig.builder(ApiConfig.Provider.DEEPSEEK).model("model").apiKey(() -> "key");
        assertThat(builder.build().baseUrl).hasToString("https://api.deepseek.com");
        assertThat(builder.contextBudget(new ContextBudget(128, true)).build().contextSizer).containsSame(BuiltinContextSizer.DEEPSEEK);
        var claude = ApiConfig.builder(ApiConfig.Provider.CLAUDE).model("model").apiKey(() -> "key").build();
        assertThatThrownBy(() -> connector().create(claude, contractContext())).isInstanceOf(IllegalArgumentException.class);
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
    protected AgentConnector<ApiConfig> connector() { return new DirectApiConnector(ApiConfig.Provider.DEEPSEEK); }

    @Override
    protected ApiConfig configuration()
    {
        return ApiConfig.builder(ApiConfig.Provider.DEEPSEEK).model(DeepSeekHttpFixture.MODEL).apiKey(() -> "sk-test-contract").baseUrl(http.baseUrl()).build();
    }
}

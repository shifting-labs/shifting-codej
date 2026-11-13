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
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestApiConnector;
import io.github.shiftinglabs.codej.sdk.testing.AnthropicHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

class TestClaudeApiConnector
        extends AbstractTestApiConnector<ClaudeApiConfig>
{
    private AnthropicHttpFixture http;

    @BeforeEach
    void setup()
            throws Exception
    {
        http = new AnthropicHttpFixture();
    }

    @AfterEach
    void cleanup() { http.close(); }

    @Override
    protected AgentConnector<ClaudeApiConfig> connector() { return new ClaudeApiConnector(); }

    @Override
    protected ClaudeApiConfig configuration()
    {
        return ClaudeApiConfig.builder(AnthropicHttpFixture.MODEL, () -> "sk-test-contract").baseUrl(http.baseUrl()).build();
    }
}

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
package io.github.shiftinglabs.codej.cli.spi.tool;

import io.github.shiftinglabs.codej.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolProviderBinding
{
    private static ToolProvider<String> provider(String id, List<ToolDefinition<?, ?>> definitions)
    {
        return new ToolProvider<>()
        {
            @Override
            public String id() { return id; }

            @Override
            public List<ToolDefinition<?, ?>> definitions(String configuration) { return definitions; }

            @Override
            public ToolSet open(String configuration, ToolSetContext context) { return null; }
        };
    }

    @Test
    void validatesOnlyTheDiagnosticIdAndNeverPrintsConfiguration()
    {
        for (String id : List.of("", " ", "bad\nid", "x".repeat(129))) {
            assertThatThrownBy(() -> ToolProviderBinding.of(provider(id, List.of()), "private configuration")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ToolProviderBinding.of(provider(null, List.of()), "value")).isInstanceOf(NullPointerException.class);
        var binding = ToolProviderBinding.of(provider("Provider Family 1", List.of()), "private configuration");
        assertThat(binding.id()).isEqualTo("Provider Family 1");
        assertThat(binding.toString()).doesNotContain("private configuration");
        assertThat(binding.definitions()).isEmpty();
    }

    @Test
    void nullCatalogsAndNullReturnedSetsAreRejected()
    {
        var binding = ToolProviderBinding.of(provider("sample", null), "value");
        assertThatThrownBy(binding::definitions).isInstanceOf(NullPointerException.class);
        ToolSetContext context = new ToolSetContext()
        {
            @Override
            public io.github.shiftinglabs.codej.cli.driver.AgentSessionContext session() { throw new AssertionError("Unused"); }

            @Override
            public io.github.shiftinglabs.codej.cli.spi.ConnectorContext connector() { throw new AssertionError("Unused"); }

            @Override
            public java.util.concurrent.Executor executor() { return Runnable::run; }
        };
        assertThatThrownBy(() -> binding.open(context)).isInstanceOf(NullPointerException.class);
    }
}

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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutions;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/** One example family shared by both API facades; no model, filesystem or process access. */
public final class ConfiguredLookupTools
        implements ToolProvider<ConfiguredLookupTools.Configuration>
{
    public record Configuration(String prefix)
    {
        public Configuration { requireNonNull(prefix, "prefix is null"); }
    }

    public record Lookup(String key)
    {
        public Lookup { requireNonNull(key, "key is null"); }
    }

    public final AtomicInteger opened = new AtomicInteger();
    public final AtomicInteger called = new AtomicInteger();
    public final AtomicInteger closed = new AtomicInteger();
    public ToolSetContext context;
    private static final ToolDefinition<Lookup, String> DEFINITION = ToolDefinitions.of(ToolDescriptor.builder("contract_lookup", "Look up a contract").build(),
            RecordToolParameters.of(Lookup.class), ToolOutput::text);
    private static final ToolRequirements REQUIREMENTS = ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();

    @Override
    public String id() { return "configured-lookup"; }

    @Override
    public List<ToolDefinition<?, ?>> definitions(Configuration configuration) { return List.of(DEFINITION); }

    @Override
    public ToolSet open(Configuration configuration, ToolSetContext assembly)
    {
        opened.incrementAndGet();
        context = assembly;
        AgentTool<Lookup, String> handler = new AgentTool<>()
        {
            @Override
            public ToolDefinition<Lookup, String> definition() { return DEFINITION; }

            @Override
            public ToolRequirements requirements(Lookup input, ToolPreparationContext preparation) { return REQUIREMENTS; }

            @Override
            public ToolExecution<String> execute(Lookup input, ToolExecutionContext invocation)
            {
                invocation.checkActive();
                if (!invocation.grants().permits(REQUIREMENTS)) {
                    throw new SecurityException("Host tool grant missing");
                }
                called.incrementAndGet();
                return ToolExecutions.completed(configuration.prefix() + input.key());
            }
        };
        return new ToolSet()
        {
            private boolean released;

            @Override
            public List<AgentTool<?, ?>> tools()
            {
                if (released) {
                    throw new IllegalStateException("Closed tool set");
                }
                return List.of(handler);
            }

            @Override
            public void close()
            {
                if (!released) {
                    closed.incrementAndGet();
                    released = true;
                }
            }
        };
    }
}

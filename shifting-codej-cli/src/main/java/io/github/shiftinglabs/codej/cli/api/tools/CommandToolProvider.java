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
package io.github.shiftinglabs.codej.cli.api.tools;

import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.tool.ToolDefinition;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Explicit command-tree installation. Adds no runtime, dependencies, processes, workers or global state. */
public final class CommandToolProvider
        implements ToolProvider<CommandToolProvider.Configuration>
{
    /** Immutable host binding. The thread-safe factory must return a fresh, session-owned backend. */
    public record Configuration(CommandLimits limits, Factory factory)
    {
        public Configuration
        {
            requireNonNull(limits, "limits is null");
            requireNonNull(factory, "factory is null");
        }

        @Override
        public String toString() { return "CommandConfiguration[limits=" + limits + ", factory=<private>]"; }
    }

    @FunctionalInterface
    public interface Factory
    {
        /**
         * Validates enforcement of all bounds and opens one session's resources, without executing
         * model-requested work. If this throws, the factory owns cleanup of partially opened resources.
         * The returned backend must also enforce path exclusions, read-only workspace, no network,
         * sanitized environment, durable prepared ownership and crash recovery. Borrowed assembly
         * services must not be closed. A factory may serve independent sessions concurrently.
         */
        Backend open(CommandLimits limits, ToolSetContext context);
    }

    /** Owned backend. Failed close retains ownership and must support retry; root exit is insufficient. */
    public interface Backend
            extends CommandExecutor, AutoCloseable
    {
        /** Verifies all owned work stopped before releasing resources; idempotent after successful close. */
        @Override
        void close();
    }

    @Override
    public String id() { return "command-execution"; }

    @Override
    public List<ToolDefinition<?, ?>> definitions(Configuration configuration)
    {
        requireNonNull(configuration, "configuration is null");
        return List.of(RunCommandTool.DEFINITION);
    }

    @Override
    public ToolSet open(Configuration configuration, ToolSetContext context)
    {
        requireNonNull(configuration, "configuration is null");
        requireNonNull(context, "context is null");
        checkArgument(requireNonNull(context.toolExecutionBudget(), "tool budget is null").isPresent(), "command trees require a shared execution budget");
        Backend backend = requireNonNull(configuration.factory().open(configuration.limits(), context), "factory returned no backend");
        return new ToolSet()
        {
            private List<AgentTool<?, ?>> tools;
            private boolean closed;

            @Override
            public synchronized List<AgentTool<?, ?>> tools()
            {
                checkState(!closed, "command tool set is closed");
                // The engine owns this set before inspection, so failed capability checks keep cleanup retryable.
                if (tools == null) {
                    tools = List.of(new RunCommandTool(backend, configuration.limits()));
                }
                return tools;
            }

            @Override
            public synchronized void close()
            {
                if (!closed) {
                    backend.close();
                    closed = true;
                }
            }
        };
    }
}

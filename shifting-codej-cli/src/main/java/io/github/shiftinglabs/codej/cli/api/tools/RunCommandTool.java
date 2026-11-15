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
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.tool.ToolDefinition;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.CommandResult;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ShellCommand;
import static java.util.Objects.requireNonNull;

/** Explicitly installed contained-tree handler; the borrowed backend owns enforcement and lifecycle. */
public final class RunCommandTool
        implements AgentTool<ShellCommand, CommandResult>
{
    static final ToolDefinition<ShellCommand, CommandResult> DEFINITION = CodingToolSupport.definition(
            "run_command", "Run absolute argv as a contained process tree with host-enforced CPU, memory, process, scratch, time and output limits; no network",
            ShellCommand.class);
    private final CommandExecutor executor;
    private final CommandLimits limits;

    public RunCommandTool(CommandExecutor executor, CommandLimits limits)
    {
        this.executor = requireNonNull(executor, "executor is null");
        this.limits = requireNonNull(limits, "limits is null");
        checkArgument(executor.supportsCommandTrees(), "backend does not support contained command trees");
    }

    @Override
    public ToolDefinition<ShellCommand, CommandResult> definition() { return DEFINITION; }

    @Override
    public ToolRequirements requirements(ShellCommand request, ToolPreparationContext context)
    {
        requireNonNull(request, "request is null");
        requireNonNull(context, "context is null");
        checkArgument(request.timeoutSeconds() <= limits.maximumSeconds(), "command exceeds host timeout limit");
        return CodingToolSupport.command();
    }

    @Override
    public CommandExecution execute(ShellCommand request, ToolExecutionContext context)
    {
        CodingToolSupport.check(requirements(request, context), context);
        return requireNonNull(executor.startTree(request, limits, context), "backend returned no execution");
    }
}

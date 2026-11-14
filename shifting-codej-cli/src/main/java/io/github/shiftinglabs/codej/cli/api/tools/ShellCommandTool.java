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
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.tool.ToolDefinition;

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.CommandResult;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ShellCommand;
import static java.util.Objects.requireNonNull;

/** Typed shell_command handler. Borrows its resource capability; it owns no filesystem, worker or process. */
public final class ShellCommandTool
        implements AgentTool<ShellCommand, CommandResult>
{
    static final ToolDefinition<ShellCommand, CommandResult> DEFINITION = CodingToolSupport.definition(
            "shell_command", "Run absolute argv in a fixed macOS sandbox: read-only workspace, private scratch, no network, no child processes", ShellCommand.class);
    private final CommandExecutor executor;

    public ShellCommandTool(CommandExecutor executor) { this.executor = requireNonNull(executor, "executor is null"); }

    @Override
    public ToolDefinition<ShellCommand, CommandResult> definition() { return DEFINITION; }

    @Override
    public ToolRequirements requirements(ShellCommand request, ToolPreparationContext context)
    {
        requireNonNull(request, "request is null");
        return CodingToolSupport.command();
    }

    @Override
    public ToolExecution<CommandResult> execute(ShellCommand request, ToolExecutionContext context)
    {
        CodingToolSupport.check(requirements(request, context), context);
        return executor.start(request, context);
    }
}

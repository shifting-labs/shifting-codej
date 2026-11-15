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

import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;

import static java.util.Objects.requireNonNull;

/**
 * Borrowed contained-command capability. The session serializes starts and owns backend cleanup.
 * Start records durable ownership before launch and may block on the caller's bounded worker.
 * A returned handle controls only its own invocation, including after a later command starts.
 */
public interface CommandExecutor
{
    ToolExecution<ToolMessages.CommandResult> start(ToolMessages.ShellCommand request, ToolExecutionContext context);

    /** Pure capability query. Existing single-process implementations remain unchanged. */
    default boolean supportsCommandTrees() { return false; }

    /**
     * Starts an explicitly bounded, contained process tree. The backend must enforce every limit,
     * persist ownership through the invocation context before effects, and keep ownership until
     * verified tree shutdown and durable result acknowledgement. Root exit is insufficient.
     * The returned handle is invocation-scoped; output retrieval performs no new execution.
     * Unsupported backends must fail before effects, without falling back to single-process start.
     */
    default CommandExecution startTree(ToolMessages.ShellCommand request, CommandLimits limits, ToolExecutionContext context)
    {
        requireNonNull(request, "request is null");
        requireNonNull(limits, "limits is null");
        requireNonNull(context, "context is null");
        throw new UnsupportedOperationException("Contained command trees are unsupported by this backend");
    }
}

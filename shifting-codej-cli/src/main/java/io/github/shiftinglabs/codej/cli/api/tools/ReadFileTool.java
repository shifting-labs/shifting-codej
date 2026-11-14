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

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.FileContent;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ReadFile;
import static java.util.Objects.requireNonNull;

/** Typed read_file handler. Borrows its resource capability; it owns no filesystem, worker or process. */
public final class ReadFileTool
        implements AgentTool<ReadFile, FileContent>
{
    static final ToolDefinition<ReadFile, FileContent> DEFINITION = CodingToolSupport.definition(
            "read_file", "Read one bounded UTF-8 workspace file and its SHA-256 preimage", ReadFile.class);
    private final WorkspaceReader reader;

    public ReadFileTool(WorkspaceReader reader) { this.reader = requireNonNull(reader, "reader is null"); }

    @Override
    public ToolDefinition<ReadFile, FileContent> definition() { return DEFINITION; }

    @Override
    public ToolRequirements requirements(ReadFile request, ToolPreparationContext context) { return CodingToolSupport.read(request.path()); }

    @Override
    public ToolExecution<FileContent> execute(ReadFile request, ToolExecutionContext context)
    {
        return CodingToolSupport.execute(requirements(request, context), context, () -> reader.read(request, context));
    }
}

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

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchResult;
import static java.util.Objects.requireNonNull;

/** Typed search_files handler. Borrows its resource capability; it owns no filesystem, worker or process. */
public final class SearchFilesTool
        implements AgentTool<SearchFiles, SearchResult>
{
    static final ToolDefinition<SearchFiles, SearchResult> DEFINITION = CodingToolSupport.definition(
            "search_files", "Bounded literal text search in UTF-8 workspace files; no regular expressions", SearchFiles.class);
    private final WorkspaceReader reader;

    public SearchFilesTool(WorkspaceReader reader) { this.reader = requireNonNull(reader, "reader is null"); }

    @Override
    public ToolDefinition<SearchFiles, SearchResult> definition() { return DEFINITION; }

    @Override
    public ToolRequirements requirements(SearchFiles request, ToolPreparationContext context) { return CodingToolSupport.read(request.directory()); }

    @Override
    public ToolExecution<SearchResult> execute(SearchFiles request, ToolExecutionContext context)
    {
        return CodingToolSupport.execute(requirements(request, context), context, () -> reader.search(request, context));
    }
}

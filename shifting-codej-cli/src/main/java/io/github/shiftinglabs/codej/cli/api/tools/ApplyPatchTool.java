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

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ApplyPatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.PatchResult;
import static java.util.Objects.requireNonNull;

/** Typed apply_patch handler. Borrows its resource capability; it owns no filesystem, worker or process. */
public final class ApplyPatchTool
        implements AgentTool<ApplyPatch, PatchResult>
{
    static final ToolDefinition<ApplyPatch, PatchResult> DEFINITION = CodingToolSupport.definition(
            "apply_patch", "Create, update or delete one workspace file; updates/deletes require expectedSha256. Exact replacements must match once", ApplyPatch.class);
    private final WorkspaceEditor editor;

    public ApplyPatchTool(WorkspaceEditor editor) { this.editor = requireNonNull(editor, "editor is null"); }

    @Override
    public ToolDefinition<ApplyPatch, PatchResult> definition() { return DEFINITION; }

    @Override
    public ToolRequirements requirements(ApplyPatch request, ToolPreparationContext context) { return CodingToolSupport.write(request.path()); }

    @Override
    public ToolExecution<PatchResult> execute(ApplyPatch request, ToolExecutionContext context)
    {
        return CodingToolSupport.execute(requirements(request, context), context, () -> editor.apply(request, context));
    }
}

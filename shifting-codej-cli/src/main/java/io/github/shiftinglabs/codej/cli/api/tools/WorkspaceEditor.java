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

import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;

import java.io.IOException;

/** Borrowed edit capability. Enforces grants, containment and preimages at use; it does not own the shared workspace lease. */
public interface WorkspaceEditor
{
    ToolMessages.PatchResult apply(ToolMessages.ApplyPatch request, ToolExecutionContext context)
            throws IOException;
}

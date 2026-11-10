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
package io.github.shiftinglabs.codej;

/**
 * How much an agent may do in its session's working directory. The provider's sandbox enforces it;
 * anything beyond it goes to the session's {@link ApprovalHandler}.
 */
public enum Access
{
    /**
     * No shell, file or web tools: the agent can only answer and call the session's own {@link Tool}s.
     * With no tools, a text-only conversation. Give it an empty working directory.
     */
    TOOLS_ONLY,

    /** Reads files and runs commands, with every write blocked. */
    READ_ONLY,

    /** Also edits files and runs commands that write, inside the working directory only. */
    WORKSPACE_WRITE
}

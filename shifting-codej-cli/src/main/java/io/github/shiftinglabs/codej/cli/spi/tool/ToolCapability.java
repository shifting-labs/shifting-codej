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
package io.github.shiftinglabs.codej.cli.spi.tool;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Open identifier for required authority. Unknown identifiers must be rejected by host policy, never auto-granted. */
public record ToolCapability(String id)
{
    public static final ToolCapability WORKSPACE_READ = new ToolCapability("workspace.read");
    public static final ToolCapability WORKSPACE_WRITE = new ToolCapability("workspace.write");
    public static final ToolCapability PROCESS_EXECUTE = new ToolCapability("process.execute");
    public static final ToolCapability NETWORK_ACCESS = new ToolCapability("network.access");
    public static final ToolCapability HOST_INVOKE = new ToolCapability("host.invoke");

    public ToolCapability
    {
        checkArgument(requireNonNull(id, "id is null").length() <= 128 && id.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"), "invalid capability id");
    }
}

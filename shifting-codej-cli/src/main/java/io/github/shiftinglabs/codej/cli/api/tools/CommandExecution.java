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

/**
 * One contained invocation. Cancellation requests a stop; stopAndVerify proves tree shutdown.
 * Completion never authorizes releasing resources before shutdown verification, and committed
 * acknowledges resources only after durable result storage. Handles cannot control later runs.
 */
public interface CommandExecution
        extends ToolExecution<ToolMessages.CommandResult>
{
    /** Nonblocking, thread-safe snapshot; bounded by the invocation's configured output limits. */
    CommandOutput output();
}

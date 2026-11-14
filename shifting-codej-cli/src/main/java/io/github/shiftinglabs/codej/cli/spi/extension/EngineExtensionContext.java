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
package io.github.shiftinglabs.codej.cli.spi.extension;

import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;

import java.nio.file.Path;
import java.time.Duration;

/** Session metadata only; no model, execution, approval or authority-changing handles. */
public interface EngineExtensionContext
{
    AgentSessionRef reference();

    ConnectorIdentity identity();

    Path worktree();

    SessionAccess access();

    Duration shutdownTimeout();
}

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

import java.util.List;

/** One session's tool implementations and owned resources. Tools must match the advertised provider contracts. */
public interface ToolSet
        extends AutoCloseable
{
    /** Immutable tool snapshot with unique model-facing names; unavailable after successful close. */
    List<AgentTool<?, ?>> tools();

    /**
     * Idempotently closes owned resources only after verifying all owned work stopped. Failed close
     * retains ownership and permits retry. Never closes borrowed host services/executors.
     */
    @Override
    void close();
}

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

import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;

/** Trusted provider assembly boundary. These borrowed host services must not be exposed wholesale to handlers. */
public interface ToolSetContext
{
    AgentSessionContext session();

    ConnectorContext connector();

    Executor executor();

    /** Positive finite bound for stop verification; providers must not replace it with an unbounded wait. */
    default Duration shutdownTimeout() { return Duration.ofSeconds(2); }

    /**
     * Borrowed engine admission service, when configured. Required-backend providers can reject
     * unbudgeted execution during open. The dispatcher acquires it; do not acquire it again from
     * a handler or close the host service. Default preserves existing assembly implementations.
     */
    default Optional<ToolExecutionBudget> toolExecutionBudget() { return Optional.empty(); }
}

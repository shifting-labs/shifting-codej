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

import io.github.shiftinglabs.codej.tool.ToolDefinition;

/**
 * One logical operation backed by scoped services. Implementations may use record or interface
 * parameters; they do not receive JSON trees, model clients, or runtime Class tokens.
 * Requirements must be effect-free. Backends enforce granted authority again at use.
 *
 * @param <P> immutable validated parameters
 * @param <R> typed result
 */
public interface AgentTool<P, R>
{
    ToolDefinition<P, R> definition();

    ToolRequirements requirements(P parameters, ToolPreparationContext context);

    /** Starts only after the caller has durably recorded intent and satisfied policy/approval. */
    ToolExecution<R> execute(P parameters, ToolExecutionContext context);
}

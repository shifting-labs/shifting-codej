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
package io.github.shiftinglabs.codej.cli.api;

import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;

import java.util.Optional;

/** Pure instruction assembly. Model selection and tool admission remain owned by the engine. */
@FunctionalInterface
interface ApiPromptAssembler
{
    ApiPromptAssembler DEFAULT = (context, command, model, effort, tools) ->
            context.scope().standingInstructions() + "\n" + context.managedInstructions();

    String assemble(AgentSessionContext context, AgentTurnCommand command, String model, Optional<String> effort, ApiToolRegistry tools);
}

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

import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Immutable request envelope; an absent contract manifest identifies pre-tool-SPI checkpoints. */
record ApiRequestSetup(String model, String instructions, Optional<String> effort, Optional<String> tier,
        List<ModelRequest.ToolDefinition> tools, Optional<List<ToolRegistration.Contract>> toolContracts)
{
    ApiRequestSetup
    {
        checkText(model, "model");
        requireNonNull(instructions, "instructions is null");
        requireNonNull(effort, "effort is null").ifPresent(value -> checkText(value, "effort"));
        requireNonNull(tier, "tier is null").ifPresent(value -> checkText(value, "tier"));
        tools = List.copyOf(tools);
        toolContracts = requireNonNull(toolContracts, "toolContracts is null").map(List::copyOf);
        checkArgument(tools.stream().map(ModelRequest.ToolDefinition::name).distinct().count() == tools.size(), "duplicate tool definition");
        toolContracts.ifPresent(contracts -> checkArgument(contracts.stream().map(ToolRegistration.Contract::name).distinct().count() == contracts.size(),
                "duplicate tool contract"));
    }

    @Override
    public String toString() { return "ApiRequestSetup[redacted]"; }
}

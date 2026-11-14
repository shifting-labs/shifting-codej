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

import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;

import static java.util.Objects.requireNonNull;

/** A fully captured turn prompt. Only the engine constructs the model/options/tool envelope. */
record ApiPromptSnapshot(String instructions, ModelInput.UserMessage input)
{
    ApiPromptSnapshot
    {
        requireNonNull(instructions, "instructions is null");
        requireNonNull(input, "input is null");
    }

    @Override
    public String toString() { return "ApiPromptSnapshot[redacted]"; }
}

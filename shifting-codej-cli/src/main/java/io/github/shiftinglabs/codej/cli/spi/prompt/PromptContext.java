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
package io.github.shiftinglabs.codej.cli.spi.prompt;

import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;

import java.time.Instant;
import java.util.Optional;

/** One turn's immutable input snapshot with a live cancellation view; retain no borrowed view after contribute returns. */
public interface PromptContext
{
    String turnId();

    String model();

    Optional<String> effort();

    ModelInput.UserMessage input();

    ModelHistory history();

    Optional<Instant> deadline();

    boolean cancelled();
}

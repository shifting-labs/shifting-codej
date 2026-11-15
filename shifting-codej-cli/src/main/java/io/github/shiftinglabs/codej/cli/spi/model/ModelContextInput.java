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
package io.github.shiftinglabs.codej.cli.spi.model;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Private adapter-projected active input, never a public event or canonical audit transcript. Do not log it. */
public record ModelContextInput(String providerId, String model, String serializedInput, int maximumOutputTokens, Optional<Instant> deadline)
{
    public static final int MAX_INPUT_CHARACTERS = 16 * 1024 * 1024;

    public ModelContextInput
    {
        checkArgument(requireNonNull(providerId, "providerId is null").matches("[a-z0-9]+(?:-[a-z0-9]+)*"), "invalid providerId");
        checkText(model, "model");
        requireNonNull(serializedInput, "serializedInput is null");
        checkArgument(!serializedInput.isBlank() && serializedInput.length() <= MAX_INPUT_CHARACTERS, "invalid projected input");
        checkArgument(maximumOutputTokens > 0, "maximumOutputTokens must be positive");
        requireNonNull(deadline, "deadline is null");
    }

    @Override
    public String toString() { return "ModelContextInput[redacted]"; }
}

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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Provider-specific joint context window, complete input size and per-response output ceiling, all in tokens. */
public record ModelContextAssessment(long contextWindowTokens, long inputTokens, int maximumOutputTokens, Accuracy accuracy)
{
    public enum Accuracy { EXACT, ESTIMATED }

    public ModelContextAssessment
    {
        checkArgument(contextWindowTokens > 0, "context window must be positive");
        checkArgument(inputTokens >= 0, "input tokens must not be negative");
        checkArgument(maximumOutputTokens > 0 && maximumOutputTokens <= contextWindowTokens, "invalid provider output limit");
        requireNonNull(accuracy, "accuracy is null");
    }

    /** Overflow-safe admission; input sizing includes provider framing and is independent of the output cap. */
    public boolean fits(int outputTokens, long safetyMarginTokens)
    {
        checkArgument(outputTokens > 0, "output tokens must be positive");
        checkArgument(safetyMarginTokens >= 0, "safety margin must not be negative");
        return outputTokens <= maximumOutputTokens && safetyMarginTokens < contextWindowTokens
                && outputTokens <= contextWindowTokens - safetyMarginTokens
                && inputTokens <= contextWindowTokens - safetyMarginTokens - outputTokens;
    }
}

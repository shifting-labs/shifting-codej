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
package io.github.shiftinglabs.codej;

import java.math.BigDecimal;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What a turn used.
 *
 * @param inputTokens tokens sent to the model, including cached ones
 * @param cachedInputTokens the part of {@code inputTokens} served from the provider's cache
 * @param outputTokens tokens the model wrote, including reasoning
 * @param cost what the turn cost in US dollars, when the provider says
 */
public record Usage(long inputTokens, long cachedInputTokens, long outputTokens, Optional<BigDecimal> cost)
{
    /** Nothing used. */
    public static final Usage NONE = new Usage(0, 0, 0, Optional.empty());

    /**
     * Creates a usage.
     *
     * @throws IllegalArgumentException when a count or the cost is negative, or {@code cachedInputTokens} exceeds {@code inputTokens}
     */
    public Usage
    {
        checkArgument(inputTokens >= 0 && cachedInputTokens >= 0 && outputTokens >= 0, "Token counts are negative: %s, %s, %s", inputTokens, cachedInputTokens, outputTokens);
        checkArgument(cachedInputTokens <= inputTokens, "cachedInputTokens %s exceeds inputTokens %s", cachedInputTokens, inputTokens);
        requireNonNull(cost, "cost is null");
        cost.ifPresent(dollars -> checkArgument(dollars.signum() >= 0, "cost is negative: %s", dollars));
    }
}

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

import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Settings for one turn that differ from the session's. Start from {@link #defaults()}.
 *
 * @param model a {@link Model#id()} for this turn instead of the session's
 * @param effort a reasoning effort for this turn
 * @param serviceTier the {@link Model.ServiceTier#id()} of one of the model's {@link Model#serviceTiers()}
 * @param timeLimit stops the turn after this long, ending it as {@link TurnResult.Failure#TIME_LIMIT}
 */
public record TurnOptions(Optional<String> model, Optional<String> effort, Optional<String> serviceTier, Optional<Duration> timeLimit)
{
    private static final TurnOptions DEFAULTS = new TurnOptions(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * Creates turn options.
     *
     * @throws IllegalArgumentException when {@code timeLimit} is not positive
     */
    public TurnOptions
    {
        requireNonNull(model, "model is null");
        requireNonNull(effort, "effort is null");
        requireNonNull(serviceTier, "serviceTier is null");
        requireNonNull(timeLimit, "timeLimit is null");
        timeLimit.ifPresent(limit -> checkArgument(limit.isPositive(), "timeLimit is not positive: %s", limit));
    }

    /**
     * The session's own settings, with no time limit.
     *
     * @return options that change nothing
     */
    public static TurnOptions defaults()
    {
        return DEFAULTS;
    }

    /**
     * These options with another model.
     *
     * @param model a {@link Model#id()}
     * @return new options
     */
    public TurnOptions withModel(String model)
    {
        return new TurnOptions(Optional.of(model), effort, serviceTier, timeLimit);
    }

    /**
     * These options with another reasoning effort.
     *
     * @param effort one of the model's {@link Model#efforts()}
     * @return new options
     */
    public TurnOptions withEffort(String effort)
    {
        return new TurnOptions(model, Optional.of(effort), serviceTier, timeLimit);
    }

    /**
     * These options with a service tier.
     *
     * @param serviceTier the {@link Model.ServiceTier#id()} of one of the model's {@link Model#serviceTiers()}
     * @return new options
     */
    public TurnOptions withServiceTier(String serviceTier)
    {
        return new TurnOptions(model, effort, Optional.of(serviceTier), timeLimit);
    }

    /**
     * These options with a time limit.
     *
     * @param timeLimit how long the turn may run; positive
     * @return new options
     * @throws IllegalArgumentException when {@code timeLimit} is not positive
     */
    public TurnOptions withTimeLimit(Duration timeLimit)
    {
        return new TurnOptions(model, effort, serviceTier, Optional.of(timeLimit));
    }
}

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

import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * An account's usage limits, as its provider reports them.
 *
 * @param plan the account's plan, when the provider names it
 * @param windows the limits that apply now, such as a five-hour one and a weekly one
 * @param message what the provider says about them, such as why it reports none
 */
public record Limits(Optional<String> plan, List<Window> windows, Optional<String> message)
{
    /** Creates limits; copies {@code windows}. */
    public Limits
    {
        requireNonNull(plan, "plan is null");
        windows = ImmutableList.copyOf(windows);
        requireNonNull(message, "message is null");
    }

    /**
     * One limit over a span of time.
     *
     * @param id the provider's id for it
     * @param label its name, to show the user
     * @param usedPercent how much of it is used, from 0
     * @param startsAt when its current span began
     * @param resetsAt when it starts over
     * @param amount the money it counts, when it limits spending
     */
    public record Window(String id, String label, double usedPercent, Optional<Instant> startsAt, Optional<Instant> resetsAt, Optional<Amount> amount)
    {
        /**
         * Creates a window.
         *
         * @throws IllegalArgumentException when {@code usedPercent} is negative or not a number
         */
        public Window
        {
            requireNonNull(id, "id is null");
            requireNonNull(label, "label is null");
            checkArgument(Double.isFinite(usedPercent) && usedPercent >= 0, "usedPercent is invalid: %s", usedPercent);
            requireNonNull(startsAt, "startsAt is null");
            requireNonNull(resetsAt, "resetsAt is null");
            requireNonNull(amount, "amount is null");
        }
    }

    /**
     * Money a limit counts.
     *
     * @param used what has been spent, in major currency units
     * @param total what may be spent, in major currency units
     * @param currency its ISO 4217 code, such as {@code USD}
     */
    public record Amount(BigDecimal used, BigDecimal total, String currency)
    {
        /** Creates an amount. */
        public Amount
        {
            requireNonNull(used, "used is null");
            requireNonNull(total, "total is null");
            requireNonNull(currency, "currency is null");
        }
    }
}

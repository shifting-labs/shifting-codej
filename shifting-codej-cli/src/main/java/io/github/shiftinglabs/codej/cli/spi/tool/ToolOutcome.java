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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Immutable logical outcome. An uncertain effect never becomes safe to replay merely because work stopped. */
public final class ToolOutcome<R>
{
    public enum State { COMPLETED, FAILED, CANCELLED, UNCERTAIN }

    private final State state;
    private final Optional<R> value;
    private final Optional<ToolFailure> failure;

    private ToolOutcome(State state, Optional<R> value, Optional<ToolFailure> failure)
    {
        this.state = state;
        this.value = value;
        this.failure = failure;
    }

    public static <R> ToolOutcome<R> completed(R value)
    {
        return new ToolOutcome<>(State.COMPLETED, Optional.of(requireNonNull(value, "value is null")), Optional.empty());
    }

    public static <R> ToolOutcome<R> failed(ToolFailure failure) { return failure(State.FAILED, failure); }

    public static <R> ToolOutcome<R> cancelled(ToolFailure failure) { return failure(State.CANCELLED, failure); }

    public static <R> ToolOutcome<R> uncertain(ToolFailure failure) { return failure(State.UNCERTAIN, failure); }

    private static <R> ToolOutcome<R> failure(State state, ToolFailure failure)
    {
        return new ToolOutcome<>(state, Optional.empty(), Optional.of(requireNonNull(failure, "failure is null")));
    }

    public State state() { return state; }

    public Optional<R> value() { return value; }

    public Optional<ToolFailure> failure() { return failure; }

    @Override
    public String toString() { return "ToolOutcome[" + state + ", redacted]"; }
}

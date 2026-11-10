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

import java.util.concurrent.CompletionStage;

/**
 * A running turn: read how it ends, add to it, or stop it. Only the library completes a turn. A turn
 * is safe to share between threads.
 */
public interface Turn
{
    /**
     * How the turn ends. The stage completes once, after the listener has returned for this turn's
     * last event.
     *
     * <p>It never completes exceptionally: a failed or interrupted turn is a {@link TurnResult} too. The
     * stage is read-only; completing the future {@link CompletionStage#toCompletableFuture()} returns
     * does not change this turn.
     *
     * @return the turn's outcome, now or later
     */
    CompletionStage<TurnResult> result();

    /**
     * Waits for {@link #result()}.
     *
     * @return how the turn ended
     * @throws InterruptedException when the waiting thread is interrupted; the turn keeps running
     */
    TurnResult await()
            throws InterruptedException;

    /**
     * Adds {@code input} to the running turn, as when a person types while the agent works.
     *
     * @param input what to add
     * @throws UnsupportedOperationException when the agent's {@link Capabilities#steering()} is false
     * @throws IllegalStateException when the turn has ended
     * @throws DeliveryUncertainException when the provider may or may not have received {@code input}
     * @throws AgentException when the provider refused {@code input}
     */
    void steer(Input input);

    /**
     * Stops the provider's work on this turn. Calling it again does nothing more.
     *
     * @return {@link #result()}, which then holds {@link TurnResult.Interrupted}, or the outcome the turn
     *         reached first
     */
    CompletionStage<TurnResult> interrupt();
}

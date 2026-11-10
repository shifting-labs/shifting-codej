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

import java.util.Optional;
import java.util.function.Consumer;

/**
 * One conversation with an agent in one working directory: a Codex thread or a Claude session.
 *
 * <p>A session runs one turn at a time; use {@link Turn#steer(Input)} to add to the running one. A
 * session is safe to share between threads.
 */
public interface Session
        extends AutoCloseable
{
    /**
     * Identifies this conversation, so it can be {@linkplain Agent#resumeSession resumed} later. It is
     * empty until the provider has saved the conversation: Claude saves a new one once it begins
     * answering, and never saves a {@link Access#TOOLS_ONLY} session without tools.
     *
     * @return the conversation's ref, to store as {@link SessionRef#asString()}; empty while it cannot be resumed
     */
    Optional<SessionRef> ref();

    /**
     * Sends {@code input} and returns once the provider has taken the turn, or could not: usually within a
     * second, longer when the session must start or resume its process. {@code listener} receives this
     * turn's events in order, one at a time, on the agent's callback executor, starting with
     * {@link AgentEvent.Started} when the provider took it; it receives none after {@link Turn#result()} completes.
     *
     * <p>A turn's result comes only once everything the turn started has stopped. When the provider
     * cannot show that, the session stops its process first; if even that cannot be shown, the session
     * takes no more turns, and its working directory is listed by {@link Agent#unconfirmedDirectories()}.
     *
     * @param input what to send
     * @param options settings for this turn that differ from the session's
     * @param listener receives the turn's events
     * @return the running turn
     * @throws IllegalStateException when another turn is running, or the session is closed or ended because its process could not be
     *         shown to have stopped
     * @throws IllegalArgumentException when an attachment's type is not in {@link Capabilities#attachmentTypes()}
     */
    Turn start(Input input, TurnOptions options, Consumer<AgentEvent> listener);

    /**
     * Sends {@code input} with the session's own model and effort and no time limit, and returns once
     * the provider has taken the turn.
     *
     * @param input what to send
     * @param listener receives the turn's events
     * @return the running turn
     * @throws IllegalStateException when another turn is running, or the session is closed or ended because its process could not be
     *         shown to have stopped
     * @throws IllegalArgumentException when an attachment's type is not in {@link Capabilities#attachmentTypes()}
     * @see #start(Input, TurnOptions, Consumer)
     */
    default Turn start(Input input, Consumer<AgentEvent> listener)
    {
        return start(input, TurnOptions.defaults(), listener);
    }

    /**
     * Sends {@code input} and waits for the turn to end.
     *
     * @param input what to send
     * @return how the turn ended
     * @throws InterruptedException when the waiting thread is interrupted; the turn is interrupted too,
     *         since nothing else holds it
     * @throws IllegalStateException when another turn is running, or the session is closed or ended because its process could not be
     *         shown to have stopped
     */
    default TurnResult run(Input input)
            throws InterruptedException
    {
        Turn turn = start(input, _ -> {});
        try {
            return turn.await();
        }
        catch (InterruptedException interrupted) {
            turn.interrupt();
            throw interrupted;
        }
    }

    /**
     * Whether the session's CLI process is still running between turns, kept to take the next one. While
     * it is, it owns the working directory, as a running turn does; {@link #close()} stops it.
     *
     * @return {@code true} when no turn runs and a process is kept
     */
    boolean keepsProcess();

    /**
     * Stops this session's CLI process, interrupting a running turn first, and returns once the
     * process has exited. The conversation can still be resumed from {@link #ref()}. Closing it
     * again does nothing, unless the process could not be shown to have exited: then it checks again.
     *
     * @throws AgentException when the process could not be shown to have exited
     */
    @Override
    void close();
}

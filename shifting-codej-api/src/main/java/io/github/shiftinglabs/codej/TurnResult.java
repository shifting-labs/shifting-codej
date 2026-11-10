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

import static java.util.Objects.requireNonNull;

/** How a turn ended. Every turn ends as exactly one of these, and this set changes only in a major release. */
public sealed interface TurnResult
{
    /**
     * How long the turn ran.
     *
     * @return the time from start to end
     */
    Duration elapsed();

    /**
     * The agent finished.
     *
     * @param finalText the agent's final answer; empty when it ended without one
     * @param usage the tokens the turn used
     * @param elapsed how long the turn ran
     */
    record Completed(String finalText, Usage usage, Duration elapsed)
            implements TurnResult
    {
        /** Creates a completed result. */
        public Completed
        {
            requireNonNull(finalText, "finalText is null");
            requireNonNull(usage, "usage is null");
            requireNonNull(elapsed, "elapsed is null");
        }
    }

    /**
     * The turn could not finish.
     *
     * @param failure why, in a form a program can act on
     * @param message the provider's explanation, to show the user
     * @param retryable whether sending the turn again may succeed, as after a dropped connection
     * @param elapsed how long the turn ran
     */
    record Failed(Failure failure, String message, boolean retryable, Duration elapsed)
            implements TurnResult
    {
        /** Creates a failed result. */
        public Failed
        {
            requireNonNull(failure, "failure is null");
            requireNonNull(message, "message is null");
            requireNonNull(elapsed, "elapsed is null");
        }
    }

    /**
     * {@link Turn#interrupt()} or {@link Session#close()} stopped the turn.
     *
     * @param elapsed how long the turn ran
     */
    record Interrupted(Duration elapsed)
            implements TurnResult
    {
        /** Creates an interrupted result. */
        public Interrupted
        {
            requireNonNull(elapsed, "elapsed is null");
        }
    }

    /** Why a turn could not finish. */
    enum Failure
    {
        /** The account's sign-in expired or was removed. Sign in again, then resume the session. */
        SIGN_IN_REQUIRED,
        /** The account reached its usage limit. */
        USAGE_LIMIT,
        /** The turn ran past its {@link TurnOptions#timeLimit()}. */
        TIME_LIMIT,
        /** The CLI process exited during the turn. */
        PROCESS_EXITED,
        /** The turn never reached the provider, so sending it again does the work only once. */
        NOT_SENT,
        /**
         * The CLI may or may not have received the turn. It is never sent again on its own, since that
         * could do the work twice; resume the session to see where it stands.
         */
        DELIVERY_UNCERTAIN,
        /** Any other error the provider reported. */
        PROVIDER_ERROR
    }
}

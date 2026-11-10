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

import static java.util.Objects.requireNonNull;

/**
 * What {@link Agent#status()} found.
 *
 * @param readiness whether sessions can start, and if not, why
 * @param version the version the CLI reported, when it ran
 * @param detail a short explanation to show the user, such as what to install or run
 * @param signInCommand the shell command that signs this agent's account in, when signing in is the fix
 * @param capabilities what this CLI version supports
 */
public record AgentStatus(Readiness readiness, Optional<String> version, String detail, Optional<String> signInCommand, Capabilities capabilities)
{
    /** Creates a status. */
    public AgentStatus
    {
        requireNonNull(readiness, "readiness is null");
        requireNonNull(version, "version is null");
        requireNonNull(detail, "detail is null");
        requireNonNull(signInCommand, "signInCommand is null");
        requireNonNull(capabilities, "capabilities is null");
    }

    /**
     * Whether sessions can start.
     *
     * @return {@code true} when {@link #readiness()} is {@link Readiness#READY}
     */
    public boolean ready()
    {
        return readiness == Readiness.READY;
    }

    /** Whether sessions can start, and if not, why. */
    public enum Readiness
    {
        /** Sessions can start. */
        READY,
        /** The CLI is not installed, or not found. */
        CLI_MISSING,
        /** The CLI's version is not one this library supports. */
        UNSUPPORTED_VERSION,
        /** The CLI runs, but the account is not signed in, or its token is gone. */
        SIGN_IN_REQUIRED,
        /** The provider could not be reached just now, as when it is busy or offline; checking again later may succeed. */
        TEMPORARILY_UNAVAILABLE,
        /** Anything else, such as a check that failed; {@link AgentStatus#detail()} says what. Checking again may help. */
        UNAVAILABLE
    }
}

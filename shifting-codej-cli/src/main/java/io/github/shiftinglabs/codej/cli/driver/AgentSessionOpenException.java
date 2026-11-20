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
package io.github.shiftinglabs.codej.cli.driver;

import static java.util.Objects.requireNonNull;

/** A failed session open still owns a launch whose shutdown could not be verified. */
public final class AgentSessionOpenException
        extends AgentUnavailableException
{
    private final Runnable cleanup;

    public AgentSessionOpenException(String message, Throwable cause, Runnable cleanup)
    {
        super(message, cause);
        this.cleanup = requireNonNull(cleanup, "cleanup is null");
    }

    /** Retry shutdown. Returns once the failed launch is shown to have stopped. */
    public void retryCleanup()
    {
        cleanup.run();
    }
}

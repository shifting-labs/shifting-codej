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

import java.io.Serial;

/**
 * The agent could not do what was asked at all: the CLI is missing or signed out, its process could
 * not start, or it could not be shown to have stopped.
 *
 * <p>A turn that ran and failed is not an exception; it ends as {@link TurnResult.Failed}.
 */
public class AgentException
        extends RuntimeException
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message what could not be done, to show the user
     */
    public AgentException(String message)
    {
        super(message);
    }

    /**
     * Creates an exception with its cause.
     *
     * @param message what could not be done, to show the user
     * @param cause what went wrong underneath
     */
    public AgentException(String message, Throwable cause)
    {
        super(message, cause);
    }
}

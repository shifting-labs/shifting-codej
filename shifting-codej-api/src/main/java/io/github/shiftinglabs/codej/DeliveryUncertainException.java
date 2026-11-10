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
 * The provider may or may not have received what was sent, as when its connection dropped mid-send. It is never sent again on its own,
 * since that could do the work twice.
 */
public class DeliveryUncertainException
        extends AgentException
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with its cause.
     *
     * @param message what was sent and why its delivery is unknown, to show the user
     * @param cause what went wrong underneath
     */
    public DeliveryUncertainException(String message, Throwable cause)
    {
        super(message, cause);
    }
}

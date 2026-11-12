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
package io.github.shiftinglabs.codej.cli.spi;

import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;

/**
 * An optional extension of the existing driver contract for factory-created connectors. Existing
 * drivers do not need to implement this interface. Session and turn behavior stays with
 * {@link io.github.shiftinglabs.codej.cli.driver.AgentSession} and
 * {@link io.github.shiftinglabs.codej.cli.driver.RunningTurn}.
 */
public interface ConnectorDriver
        extends AgentDriver, AutoCloseable
{
    /** Stable runtime and profile identity, independent of the current credential value. */
    ConnectorIdentity identity();

    /**
     * Checks whether this driver can enforce every requested session constraint, without starting
     * processes, making network requests, invoking tools or emitting events. Unsupported
     * constraints must be rejected, never ignored or replaced with prompt instructions.
     *
     * <p>Every implementation of {@link #open} must perform this validation before any session
     * side effect, even when the caller has already called it. A successful check does not prove
     * that remote credentials are valid or guarantee that a later open will succeed.
     *
     * @param context the requested session, including permissions and any resume reference
     * @throws IllegalArgumentException when the context is invalid
     * @throws io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException when the driver
     *         cannot enforce the requested constraints or resume the reference
     */
    void validate(AgentSessionContext context);

    /**
     * Stops this driver's sessions and closes its own transports and resources. Closing is
     * idempotent and never closes borrowed {@link ConnectorContext} services or deletes saved
     * conversation history. An unverified shutdown must be reported as a failure, not success.
     */
    @Override
    void close();
}

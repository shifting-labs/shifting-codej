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

/**
 * Creates a complete agent runtime from its own typed configuration. Authentication selects how
 * that runtime connects; using an API key does not turn a runtime connector into a model client.
 *
 * <p>This factory is optional: existing implementations of
 * {@link io.github.shiftinglabs.codej.cli.driver.AgentDriver} remain usable without it.
 *
 * @param <C> this connector's configuration type; credentials belong in this configuration, not
 *            in {@link ConnectorContext} or {@link ConnectorIdentity}
 */
public interface AgentConnector<C>
{
    /** The stable connector ID; it must equal the created driver's identity's connector ID. */
    String id();

    /** The concrete configuration type accepted by {@link #create}. */
    Class<C> configurationType();

    /**
     * Validates configuration and creates an independently closeable driver, without starting
     * provider processes or executing model turns or inference requests. Profile preparation,
     * runtime artifact resolution and credential suppliers retain their connector's documented
     * timing and may perform I/O. The caller owns the returned driver; services in {@code context}
     * remain borrowed. A failure must release any connector-owned resources.
     *
     * @param configuration provider-specific configuration; never inferred from another connector
     * @param context host-provided profile and services
     * @return the driver bound to this configuration and profile
     * @throws IllegalArgumentException when the configuration is invalid
     */
    ConnectorDriver create(C configuration, ConnectorContext context);
}

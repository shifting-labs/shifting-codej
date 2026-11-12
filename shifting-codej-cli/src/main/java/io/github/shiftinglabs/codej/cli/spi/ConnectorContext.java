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

import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;

import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Immutable host-provided context for one connector profile. The process supervisor is borrowed;
 * the connector owns any provider-specific tool adapter or MCP broker it creates. Session tools
 * and approval handlers arrive through
 * {@link io.github.shiftinglabs.codej.cli.driver.AgentSessionContext}.
 */
public final class ConnectorContext
{
    private final Path profileDirectory;
    private final ProcessSupervisor processSupervisor;
    private final ToolServer toolServer;

    /**
     * Binds an absolute profile directory and borrowed host services. This constructor performs
     * no filesystem I/O and preserves the supplied path verbatim, including any {@code ..}
     * segments, so introducing the factory does not change existing path-based lane identities.
     *
     * <p>This three-argument constructor remains available as the context evolves; future optional
     * settings must use additive constructors, accessors or a builder.
     *
     * @param profileDirectory the profile's absolute directory; the host decides when to prepare it
     * @param processSupervisor host-owned process lifecycle service; never closed by a connector
     * @param toolServer the host tool server's name and provider naming conventions
     * @throws IllegalArgumentException when the profile directory is relative
     */
    public ConnectorContext(Path profileDirectory, ProcessSupervisor processSupervisor, ToolServer toolServer)
    {
        this.profileDirectory = requireNonNull(profileDirectory, "profileDirectory is null");
        checkArgument(profileDirectory.isAbsolute(), "profileDirectory is not absolute");
        this.processSupervisor = requireNonNull(processSupervisor, "processSupervisor is null");
        this.toolServer = requireNonNull(toolServer, "toolServer is null");
    }

    public Path profileDirectory()
    {
        return profileDirectory;
    }

    public ProcessSupervisor processSupervisor()
    {
        return processSupervisor;
    }

    public ToolServer toolServer()
    {
        return toolServer;
    }
}

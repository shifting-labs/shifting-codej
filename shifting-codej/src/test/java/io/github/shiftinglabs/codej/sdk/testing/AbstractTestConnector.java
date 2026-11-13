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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared factory contract. Subclasses supply configuration that needs no provider process or network. */
public abstract class AbstractTestConnector<C>
{
    @TempDir
    private Path contractDirectory;

    protected abstract AgentConnector<C> connector();

    protected abstract C configuration();

    protected final Path contractDirectory()
    {
        return contractDirectory;
    }

    @Test
    protected void factoryDeclaresItsConfigurationAndStableIdentity()
    {
        AgentConnector<C> factory = connector();
        C configuration = configuration();
        assertThat(factory.configurationType()).isEqualTo(configuration.getClass());
        BorrowedSupervisor supervisor = new BorrowedSupervisor();
        ConnectorContext context = context(supervisor);
        try (ConnectorDriver first = factory.create(configuration, context);
                ConnectorDriver second = factory.create(configuration, context)) {
            assertThat(first).isNotSameAs(second);
            assertThat(first.identity().connectorId()).isEqualTo(factory.id());
            assertThat(second.identity()).isEqualTo(first.identity());
            assertThat(second.id()).isEqualTo(first.id());
        }
        assertThat(supervisor.closed).isFalse();
    }

    @Test
    protected void closingOneDriverLeavesAnotherUsable()
    {
        ConnectorContext context = contractContext();
        ConnectorDriver first = connector().create(configuration(), context);
        try (ConnectorDriver second = connector().create(configuration(), context)) {
            first.close();
            second.validate(contractSessionContext());
            assertThat(second.identity()).isEqualTo(first.identity());
        }
        finally {
            first.close();
        }
    }

    @Test
    protected void rejectsMissingFactoryInputsBeforePreparingProfiles()
    {
        AgentConnector<C> factory = connector();
        ConnectorContext context = context(new BorrowedSupervisor());
        assertThatThrownBy(() -> factory.create(null, context)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> factory.create(configuration(), null)).isInstanceOf(NullPointerException.class);
        assertThat(context.profileDirectory()).doesNotExist();
    }

    @Test
    protected void validationAndClosePreserveHostServicesAndSavedHistory()
            throws Exception
    {
        BorrowedSupervisor supervisor = new BorrowedSupervisor();
        ConnectorContext context = context(supervisor);
        ConnectorDriver driver = connector().create(configuration(), context);
        AgentSessionContext session = contractSessionContext();
        Path history = Files.writeString(Files.createDirectories(context.profileDirectory()).resolve("saved-conversation"), "Keep this conversation");
        try {
            driver.validate(session);
            driver.validate(session);
            assertThatThrownBy(() -> driver.validate(null)).isInstanceOf(NullPointerException.class);
            assertThat(session.runtimeDirectory()).doesNotExist();
        }
        finally {
            driver.close();
        }
        driver.close();
        assertThatThrownBy(() -> driver.validate(session)).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> driver.open(session, event -> {})).isInstanceOf(AgentUnavailableException.class);
        assertThat(history).hasContent("Keep this conversation");
        assertThat(supervisor.closed).isFalse();
    }

    /** A supported session for the factory validation contract; existing CLI bindings retain their read-only default. */
    protected AgentSessionContext contractSessionContext()
    {
        return new AgentSessionContext("contract-task", contractDirectory, contractDirectory.resolve("runtime"),
                contractDirectory.resolve("runtime/provider"), AgentPermissionPolicy.AUTO, Optional.empty(),
                (request, timeout) -> { throw new AssertionError("Unexpected approval"); }, Optional.empty(), Optional.empty(), SessionAccess.READ_ONLY, "");
    }

    protected final ConnectorContext contractContext()
    {
        return context(new BorrowedSupervisor());
    }

    private ConnectorContext context(BorrowedSupervisor supervisor)
    {
        return new ConnectorContext(contractDirectory.resolve("profile"), supervisor, new ToolServer("contract_tools"));
    }

    private static final class BorrowedSupervisor
            implements ProcessSupervisor, AutoCloseable
    {
        private boolean closed;

        @Override
        public PreparedProcess prepare(ProcessSpec spec) { throw new AssertionError("Contract tests must not start provider processes"); }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses() { throw new AssertionError("Unexpected process recovery"); }

        @Override
        public OwnedProcessRecovery recoverOwnedProcess(String launchId) { throw new AssertionError("Unexpected process recovery"); }

        @Override
        public boolean launchedHere(String launchId) { throw new AssertionError("Unexpected process query"); }

        @Override
        public void acknowledge(String launchId) { throw new AssertionError("Unexpected acknowledgement"); }

        @Override
        public void close() { closed = true; }
    }
}

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

import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderTurnRef;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestConnectorSpi
{
    @TempDir
    private Path directory;

    @Test
    void validatesPortableIdentityWithoutEchoingRejectedValues()
    {
        assertThat(new ConnectorIdentity("claude-code", "Work.profile_1").profileId()).isEqualTo("Work.profile_1");
        assertThat(new ConnectorIdentity("codex", "codex-account-012abc").connectorId()).isEqualTo("codex");
        for (String invalid : List.of("", " ", "../profile", "secret\nvalue", "profile:other")) {
            assertThatThrownBy(() -> new ConnectorIdentity("codex", invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid profileId");
        }
        assertThatThrownBy(() -> new ConnectorIdentity("Secret value", "work"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid connectorId");
        assertThatThrownBy(() -> new ConnectorIdentity(null, "work")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectorIdentity("codex", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void contextPreservesTheProfilePathAndBorrowsServicesWithoutPreparingAnything()
    {
        Path profile = directory.resolve("missing/../profile");
        NoProcesses supervisor = new NoProcesses();
        ToolServer tools = new ToolServer("host_tools");
        ConnectorContext context = new ConnectorContext(profile, supervisor, tools);
        assertThat(context.profileDirectory()).isSameAs(profile);
        assertThat(context.profileDirectory()).isNotEqualTo(profile.normalize()).doesNotExist();
        assertThat(directory.resolve("missing")).doesNotExist();
        assertThat(context.processSupervisor()).isSameAs(supervisor);
        assertThat(context.toolServer()).isSameAs(tools);
        assertThatThrownBy(() -> new ConnectorContext(Path.of("relative"), supervisor, tools))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
        assertThatThrownBy(() -> new ConnectorContext(null, supervisor, tools)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectorContext(profile, null, tools)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConnectorContext(profile, supervisor, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void typedFactoryAddsValidationAndOwnershipWithoutChangingLegacySessionsOrTurns()
    {
        AgentDriver legacy = new LegacyDriver();
        assertThat(legacy).isNotInstanceOf(ConnectorDriver.class);
        assertThat(legacy.models()).isEmpty();
        NoProcesses supervisor = new NoProcesses();
        ConnectorContext context = new ConnectorContext(directory.resolve("profile"), supervisor, ToolServer.DEFAULT);
        AgentConnector<String> factory = new AgentConnector<>()
        {
            @Override
            public String id() { return "fixture"; }

            @Override
            public Class<String> configurationType() { return String.class; }

            @Override
            public ConnectorDriver create(String profile, ConnectorContext services)
            {
                assertThat(services).isSameAs(context);
                return new FactoryDriver(new ConnectorIdentity(id(), profile));
            }
        };
        assertThat(factory.configurationType()).isEqualTo(String.class);
        FactoryDriver connection;
        try (ConnectorDriver driver = factory.create("work", context)) {
            connection = (FactoryDriver) driver;
            assertThat(driver.identity()).isEqualTo(new ConnectorIdentity(factory.id(), "work"));
            assertThat(driver.probe().available()).isTrue();
            assertThatThrownBy(() -> driver.open(sessionContext(SessionAccess.FULL), event -> {}))
                    .isInstanceOf(AgentUnavailableException.class).hasMessage("Only read-only sessions are supported");
            assertThat(connection.opened).isZero();
            try (AgentSession first = driver.open(sessionContext(SessionAccess.READ_ONLY), event -> {});
                    AgentSession second = driver.open(sessionContext(SessionAccess.READ_ONLY), event -> {})) {
                assertThat(first.reference()).isNotEqualTo(second.reference());
                RunningTurn turn = first.start(new AgentTurnCommand("turn", "hello", List.of(), Duration.ZERO));
                assertThat(turn.completion().toCompletableFuture().join().finalResponse()).contains("hello");
            }
            assertThat(connection.opened).isEqualTo(2);
        }
        connection.close();
        assertThat(connection.closed).isTrue();
        assertThat(supervisor.closed).isFalse();
        assertThat(context.profileDirectory()).doesNotExist();
    }

    private AgentSessionContext sessionContext(SessionAccess access)
    {
        return new AgentSessionContext("task", directory, directory.resolve("runtime"), directory.resolve("runtime/provider"),
                AgentPermissionPolicy.AUTO, Optional.empty(), (request, timeout) -> { throw new AssertionError("Unexpected approval"); },
                Optional.empty(), Optional.empty(), access, "");
    }

    /** Implements only the unchanged legacy SPI, including its existing record constructors. */
    private static class LegacyDriver
            implements AgentDriver
    {
        int opened;

        @Override
        public AgentLaneId id() { return new AgentLaneId("fixture"); }

        @Override
        public AgentCapabilities capabilities() { return AgentCapabilities.none(); }

        @Override
        public AgentProviderStatus probe()
        {
            return new AgentProviderStatus(id(), AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", capabilities());
        }

        @Override
        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            AgentSessionRef reference = new AgentSessionRef(id(), 1, "session-" + ++opened);
            return new AgentSession()
            {
                @Override
                public AgentSessionRef reference() { return reference; }

                @Override
                public RunningTurn start(AgentTurnCommand command)
                {
                    return new RunningTurn()
                    {
                        @Override
                        public ProviderTurnRef reference() { return new ProviderTurnRef(command.turnId()); }

                        @Override
                        public CompletionStage<AgentTurnResult> completion()
                        {
                            return CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.COMPLETED,
                                    Optional.of(command.message()), Optional.empty(), Duration.ZERO, true));
                        }

                        @Override
                        public void cancel() {}
                    };
                }

                @Override
                public void close() {}
            };
        }
    }

    private static final class FactoryDriver
            extends LegacyDriver
            implements ConnectorDriver
    {
        private final ConnectorIdentity identity;
        private boolean closed;

        private FactoryDriver(ConnectorIdentity identity) { this.identity = identity; }

        @Override
        public ConnectorIdentity identity() { return identity; }

        @Override
        public void validate(AgentSessionContext context)
        {
            if (closed || context.access() != SessionAccess.READ_ONLY) {
                throw new AgentUnavailableException("Only read-only sessions are supported");
            }
        }

        @Override
        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            validate(context);
            return super.open(context, events);
        }

        @Override
        public void close() { closed = true; }
    }

    private static final class NoProcesses
            implements ProcessSupervisor, AutoCloseable
    {
        private boolean closed;

        @Override
        public PreparedProcess prepare(ProcessSpec spec) { throw new AssertionError("Unexpected process preparation"); }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses() { throw new AssertionError("Unexpected recovery"); }

        @Override
        public OwnedProcessRecovery recoverOwnedProcess(String launchId) { throw new AssertionError("Unexpected recovery"); }

        @Override
        public boolean launchedHere(String launchId) { throw new AssertionError("Unexpected process query"); }

        @Override
        public void acknowledge(String launchId) { throw new AssertionError("Unexpected acknowledgement"); }

        @Override
        public void close() { closed = true; }
    }
}

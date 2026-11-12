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
package io.github.shiftinglabs.codej.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestCliConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeConnector
        extends AbstractTestCliConnector<DriverAgent.ClaudeSettings>
{
    private final ClaudeConnector connector = new ClaudeConnector();
    private final CapturingSupervisor supervisor = new CapturingSupervisor();

    @TempDir
    private Path root;

    @Test
    void defaultConstructionPreparesOnlyTheHostProfileAndKeepsInheritedCredentials()
            throws Exception
    {
        Path home = root.resolve("host-profile");
        assertThat(connector.configurationType()).isEqualTo(DriverAgent.ClaudeSettings.class);
        try (ConnectorDriver driver = connector.create(settings(Optional.empty(), Optional.empty(), Optional.empty()), context(home))) {
            assertThat(driver.identity()).isEqualTo(new ConnectorIdentity("claude-code", "profile-" + hash(home)));
            assertThat(driver.id()).isEqualTo(AgentLaneId.CLAUDE_CLI);
            assertThat(driver.label()).isEqualTo("Claude Code");
            assertThat(home).isDirectory();
            assertThat(Files.getPosixFilePermissions(home)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(root.resolve("ignored-settings-profile")).doesNotExist();
            assertThat(SessionSweeps.keeps(home)).isEmpty();
            assertThat(supervisor.specs).isEmpty();

            driver.probe();
            ProcessSpec spec = supervisor.specs.getFirst();
            assertThat(spec.argv()).endsWith("claude", "--version");
            assertThat(spec.environment().get("CLAUDE_CONFIG_DIR")).isEqualTo(home.toString());
            for (String name : List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN")) {
                if (System.getenv(name) != null) {
                    assertThat(System.getenv(name).equals(spec.environment().get(name))).as("inherited %s", name).isTrue();
                }
            }
        }
        assertThat(supervisor.closes).isZero();
    }

    @Test
    void namedAccountsUseTheUnnormalizedProfileForTheirLane()
            throws Exception
    {
        Files.createDirectory(root.resolve("parent"));
        Path home = root.resolve("parent/../account-profile");
        try (ConnectorDriver account = connector.create(settings(Optional.of("work@example.com"), Optional.empty(), Optional.empty()), context(home));
                ConnectorDriver plain = connector.create(settings(Optional.empty(), Optional.empty(), Optional.empty()), context(home))) {
            assertThat(account.id()).isEqualTo(new AgentLaneId("claude-account-" + hash(home)));
            assertThat(account.id()).isNotEqualTo(new AgentLaneId("claude-account-" + hash(home.normalize())));
            assertThat(account.identity()).isEqualTo(plain.identity());
            assertThat(account.label()).isEqualTo("Claude · work@example.com");
            account.probe();
            assertThat(supervisor.specs.getFirst().environment())
                    .containsEntry("CLAUDE_CONFIG_DIR", home.normalize().toString())
                    .doesNotContainKeys("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN");
        }
    }

    @Test
    void gatewayTokenIsResolvedOnceAtCreationWithoutChangingAccountPrecedence()
    {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> token = new AtomicReference<>("initial-token");
        DriverAgent.Gateway gateway = new DriverAgent.Gateway(URI.create("https://gateway.example/anthropic"), () -> {
            reads.incrementAndGet();
            return token.get();
        });
        Path home = root.resolve("gateway");
        DriverAgent.ClaudeSettings settings = settings(Optional.of("work@example.com"), Optional.of(gateway), Optional.empty());
        assertThat(reads).hasValue(0);
        try (ConnectorDriver driver = connector.create(settings, context(home))) {
            assertThat(reads).hasValue(1);
            assertThat(driver.id()).isEqualTo(new AgentLaneId("claude-account-" + hash(home)));
            assertThat(driver.label()).isEqualTo("Claude · work@example.com");
            assertThat(supervisor.specs).isEmpty();
            token.set("rotated-token");
            driver.probe();
            assertThat(reads).hasValue(1);
            assertThat(supervisor.specs.getFirst().environment())
                    .containsEntry("ANTHROPIC_AUTH_TOKEN", "initial-token")
                    .containsEntry("ANTHROPIC_BASE_URL", "https://gateway.example/anthropic")
                    .doesNotContainKeys("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN");
        }
    }

    @Test
    void connectorOwnsItsSweepAndSessionsAndUsesTheHostToolServer()
            throws Exception
    {
        Path home = root.resolve("retained");
        Path runtime = Files.createDirectory(root.resolve("runtime"));
        Path provider = runtime.resolve("claude");
        AgentPermissionScope scope = new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false)
                .withNetwork(true).withUnsandboxedCommands(List.of("mvn *")).withUnixSockets(List.of("/var/run/docker.sock"))
                .withReviewer(List.of("Builds fetch dependencies"), List.of("Never push"), true);
        AgentSessionContext sessionContext = new AgentSessionContext("task", root, runtime, provider, scope, Optional.empty(),
                (_, _) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), Optional.empty(), Optional.empty(), "", List.of(), false);
        ConnectorDriver driver = connector.create(settings(Optional.empty(), Optional.empty(), Optional.of(Duration.ofDays(7))),
                new ConnectorContext(home, supervisor, new ToolServer("host_tools")));
        try {
            assertThat(SessionSweeps.keeps(home)).containsExactly(Duration.ofDays(7));
            AgentSession session = driver.open(sessionContext, _ -> {});
            assertThat(session.reference()).satisfies(reference -> {
                assertThat(reference.laneId()).isEqualTo(AgentLaneId.CLAUDE_CLI);
                assertThat(reference.contractVersion()).isEqualTo(1);
            });
            ObjectMapper mapper = new ObjectMapper();
            JsonNode mcp = mapper.readTree(Files.readString(provider.resolve("codej-mcp.json")));
            assertThat(mcp.path("mcpServers").has("host_tools")).isTrue();
            JsonNode sandbox = mapper.readTree(Files.readString(provider.resolve("sandbox-settings.json")));
            assertThat(sandbox.path("sandbox").path("excludedCommands").toString()).contains("mvn *");
            assertThat(sandbox.path("sandbox").path("network").path("allowUnixSockets").toString()).contains("/var/run/docker.sock");
            assertThat(sandbox.toString()).contains("Builds fetch dependencies", "Never push");
            assertThat(supervisor.specs).isEmpty();
        }
        finally {
            driver.close();
        }
        driver.close();
        assertThat(SessionSweeps.keeps(home)).isEmpty();
        assertThat(provider.resolve("codej-mcp.json")).doesNotExist();
        assertThat(home).isDirectory();
        assertThat(supervisor.closes).isZero();
        assertThatThrownBy(() -> driver.open(sessionContext, _ -> {})).hasMessage("Claude driver is closed");
    }

    @Test
    void invalidResumeIsRejectedBeforeSessionFilesAreCreated()
    {
        Path runtime = root.resolve("uncreated-runtime");
        AgentSessionContext invalid = new AgentSessionContext("task", root, runtime, runtime.resolve("provider"),
                AgentPermissionPolicy.AUTO, Optional.of(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "saved")),
                (_, _) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), Optional.empty(), Optional.empty(), SessionAccess.FULL, "");
        try (ConnectorDriver driver = connector.create(settings(Optional.empty(), Optional.empty(), Optional.empty()), context(root.resolve("profile")))) {
            assertThatThrownBy(() -> driver.validate(invalid)).hasMessage("Unsupported Claude session reference");
            assertThatThrownBy(() -> driver.open(invalid, _ -> {})).hasMessage("Unsupported Claude session reference");
            assertThat(runtime).doesNotExist();
            assertThat(supervisor.specs).isEmpty();
        }
    }

    @Test
    void preparationFailureRetainsItsExistingDiagnosticAndRegistersNoSweep()
            throws Exception
    {
        Path home = Files.writeString(root.resolve("file"), "not a directory");
        assertThatThrownBy(() -> connector.create(settings(Optional.empty(), Optional.empty(), Optional.of(Duration.ofDays(1))), context(home)))
                .isInstanceOf(AgentException.class).hasMessage("Claude's config folder could not be prepared: " + home);
        assertThat(SessionSweeps.keeps(home)).isEmpty();
        assertThat(supervisor.specs).isEmpty();
        assertThat(supervisor.closes).isZero();
    }

    @Override
    protected ClaudeConnector connector()
    {
        return connector;
    }

    @Override
    protected DriverAgent.ClaudeSettings configuration(Path executable)
    {
        return new DriverAgent.ClaudeSettings(Optional.of(executable), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), List.of(), List.of(), List.of(), false, Optional.empty(), Optional.empty(), ToolServer.DEFAULT);
    }

    private ConnectorContext context(Path home)
    {
        return new ConnectorContext(home, supervisor, ToolServer.DEFAULT);
    }

    private DriverAgent.ClaudeSettings settings(Optional<String> account, Optional<DriverAgent.Gateway> gateway, Optional<Duration> keep)
    {
        return new DriverAgent.ClaudeSettings(Optional.empty(), Optional.of(root.resolve("ignored-settings-profile")), Optional.empty(), account, gateway,
                List.of(), List.of(), List.of(), List.of(), false, keep, Optional.empty(), ToolServer.DEFAULT);
    }

    private static String hash(Path home)
    {
        return Hashing.sha256().hashString(home.toString(), StandardCharsets.UTF_8).toString().substring(0, 12);
    }

    private static final class CapturingSupervisor
            implements ProcessSupervisor, AutoCloseable
    {
        private final List<ProcessSpec> specs = new ArrayList<>();
        private int closes;

        @Override
        public PreparedProcess prepare(ProcessSpec spec)
        {
            specs.add(spec);
            throw new AgentUnavailableException("No provider process starts in this fixture");
        }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses()
        {
            throw new AssertionError("Connector must not recover borrowed supervisor processes");
        }

        @Override
        public OwnedProcessRecovery recoverOwnedProcess(String launchId)
        {
            throw new AssertionError("Connector must not recover borrowed supervisor processes");
        }

        @Override
        public boolean launchedHere(String launchId)
        {
            return false;
        }

        @Override
        public void acknowledge(String launchId)
        {
            throw new AssertionError("No process was started");
        }

        @Override
        public void close()
        {
            closes++;
        }
    }
}

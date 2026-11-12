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

import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.AgentStatus;
import io.github.shiftinglabs.codej.Claude;
import io.github.shiftinglabs.codej.Codex;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestConnectorIntegration
{
    @TempDir
    private Path directory;

    @Test
    void publicCodexBuilderUsesTheConnectorWithoutResolvingItsKey()
    {
        Path home = directory.resolve("codex-profile");
        Duration keep = Duration.ofDays(5);
        Executor callbacks = Runnable::run;
        try (DriverAgent agent = (DriverAgent) Codex.builder()
                .executable(directory.resolve("never-run-codex"))
                .stateDirectory(directory.resolve("codex-state")).home(home)
                .apiKey(() -> { throw new AssertionError("Building an agent must not read the API key"); })
                .callbackExecutor(callbacks).keepSessionsFor(keep).build()) {
            assertThat(agent.driver()).isInstanceOfSatisfying(CodexConnectorDriver.class, driver -> {
                assertThat(driver.identity().connectorId()).isEqualTo("codex");
                assertThat(driver.testedVersion()).contains(Codex.VERSION);
                assertThat(driver.unavailable()).isEmpty();
                assertThat(driver.label()).isEqualTo("Codex · OpenAI API key");
            });
            assertThat(agent.callbacks()).isSameAs(callbacks);
            assertThat(SessionSweeps.keeps(home)).containsExactly(keep);
        }
        assertThat(SessionSweeps.keeps(home)).isEmpty();
    }

    @Test
    void compatibilityEntryPointPreservesAnUnavailableDownloadWithoutProbingTheFallback()
    {
        String classifier = "osx-aarch_64";
        CodexBinary binary = new CodexBinary(directory.resolve("cache"), URI.create("http://127.0.0.1:1/"), classifier, Map.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        DriverAgent.CodexSettings settings = new DriverAgent.CodexSettings(Optional.empty(), Optional.empty(), Optional.of(directory.resolve("state")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ToolServer.DEFAULT);
        String diagnostic = "This library records no Codex " + Codex.VERSION + " for " + classifier + "; set Codex.builder().executable(path)";

        try (DriverAgent agent = (DriverAgent) DriverAgent.codex(settings, binary)) {
            assertThat(agent.driver()).isInstanceOfSatisfying(CodexConnectorDriver.class, driver -> {
                assertThat(driver.unavailable()).contains(diagnostic);
                assertThat(driver.testedVersion()).isEmpty();
                assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.CLI_MISSING);
                assertThat(driver.probe().diagnostic()).isEqualTo(diagnostic);
            });
            AgentStatus status = agent.status();
            assertThat(status.readiness()).isEqualTo(AgentStatus.Readiness.CLI_MISSING);
            assertThat(status.detail()).isEqualTo(diagnostic);
            assertThat(status.version()).isEmpty();
            assertThat(agent.limits().message()).contains(diagnostic);
            assertThatThrownBy(agent::models).isInstanceOf(AgentException.class).hasMessage(diagnostic);
            assertThatThrownBy(() -> agent.startSession(SessionOptions.in(directory).build()))
                    .isInstanceOf(AgentException.class).hasMessage(diagnostic);
        }
        assertThat(directory.resolve("cache")).doesNotExist();
    }

    @Test
    void publicClaudeBuilderPreservesCallbacksScopeAndExactlyOneRetentionRegistration()
    {
        Path home = directory.resolve("claude-profile");
        Duration keep = Duration.ofDays(7);
        Executor callbacks = Runnable::run;
        try (DriverAgent agent = (DriverAgent) Claude.builder()
                .executable(directory.resolve("never-run-claude"))
                .stateDirectory(directory.resolve("claude-state")).configDir(home)
                .runOutsideSandbox("mvn").allowUnixSocket(Path.of("/var/run/docker.sock"))
                .autoModeRules("Never push").autoModeContext("Downloads reach Maven Central").reviewSandboxedCommands()
                .callbackExecutor(callbacks).keepSessionsFor(keep).build()) {
            assertThat(agent.driver()).isInstanceOf(ConnectorDriver.class);
            assertThat(((ConnectorDriver) agent.driver()).identity().connectorId()).isEqualTo("claude-code");
            assertThat(agent.callbacks()).isSameAs(callbacks);
            assertThat(SessionSweeps.keeps(home)).containsExactly(keep);

            AgentPermissionScope scope = new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false);
            AgentPermissionScope networked = agent.sandbox().apply(scope.withNetwork(true));
            assertThat(networked.unsandboxedCommands()).containsExactly("mvn *");
            assertThat(networked.unixSockets()).containsExactly("/var/run/docker.sock");
            assertThat(networked.reviewerRules()).containsExactly("Never push");
            assertThat(networked.reviewerContext()).containsExactly("Downloads reach Maven Central");
            assertThat(networked.reviewSandboxedCommands()).isTrue();
            AgentPermissionScope offline = agent.sandbox().apply(scope);
            assertThat(offline.unsandboxedCommands()).isEmpty();
            assertThat(offline.unixSockets()).isEmpty();
            assertThat(offline.reviewerRules()).containsExactly("Never push");
        }
        assertThat(SessionSweeps.keeps(home)).isEmpty();
    }
}

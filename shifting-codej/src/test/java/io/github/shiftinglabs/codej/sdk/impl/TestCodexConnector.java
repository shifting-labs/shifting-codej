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

import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.Codex;
import io.github.shiftinglabs.codej.cli.codex.CodexGateway;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.OwnedProcessRecovery;
import io.github.shiftinglabs.codej.cli.process.PreparedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexConnector
{
    private static final String CLASSIFIER = "osx-aarch_64";
    private static final String EMAIL = "me@example.com";

    @TempDir
    private Path directory;

    private final NoProcesses supervisor = new NoProcesses();
    private final AtomicInteger credentialReads = new AtomicInteger();

    @ParameterizedTest
    @EnumSource(Mode.class)
    void constructsEveryModeWithoutReadingCredentialsOrResolvingAnExplicitBinary(Mode mode)
            throws IOException
    {
        CodexConnector factory = factory();
        Path home = directory.resolve("home");
        assertThat(factory.id()).isEqualTo("codex");
        assertThat(factory.configurationType()).isEqualTo(CodexConnectorConfig.class);

        try (CodexConnectorDriver driver = factory.create(configuration(mode), context(home))) {
            String profile = profile(home);
            assertThat(driver.identity()).isEqualTo(new ConnectorIdentity(factory.id(), "home-" + profile));
            assertThat(driver.id().value()).isEqualTo(switch (mode) {
                case DEFAULT -> "codex";
                case ACCOUNT, GATEWAY, API_KEY -> "codex-account-" + profile;
                case DEEPSEEK -> "deepseek-key-" + profile;
            });
            assertThat(driver.label()).isEqualTo(switch (mode) {
                case DEFAULT -> "Codex";
                case ACCOUNT -> "Codex · " + EMAIL;
                case GATEWAY -> "Codex gateway";
                case API_KEY -> "Codex · OpenAI API key";
                case DEEPSEEK -> "DeepSeek";
            });
            assertThat(driver.unavailable()).isEmpty();
            assertThat(driver.testedVersion()).contains(Codex.VERSION);
            driver.validate(sessionContext());
            assertThat(credentialReads).hasValue(0);
            if (mode == Mode.DEEPSEEK) {
                assertThat(home).doesNotExist();
            }
            else {
                assertThat(home.resolve("config.toml")).hasContent("");
                assertThat(Files.getPosixFilePermissions(home)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
                assertThat(Files.getPosixFilePermissions(home.resolve("config.toml"))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            }
        }
        assertThat(directory.resolve("cache")).doesNotExist();
        assertThat(supervisor.closed).isFalse();
    }

    @Test
    void retainsTheExactAbsoluteHomeSpellingForItsLaneAndIdentity()
            throws IOException
    {
        Files.createDirectory(directory.resolve("middle"));
        Path home = directory.resolve("middle/../home");

        try (CodexConnectorDriver driver = factory().create(configuration(Mode.ACCOUNT), context(home))) {
            assertThat(driver.id().value()).isEqualTo("codex-account-" + profile(home));
            assertThat(driver.identity().profileId()).isEqualTo("home-" + profile(home));
            assertThat(driver.identity().profileId()).isNotEqualTo("home-" + profile(home.normalize()));
            assertThat(home.resolve("config.toml")).exists();
        }
    }

    @Test
    void preservesAnExistingLoginAndConfiguration()
            throws IOException
    {
        Path home = Files.createDirectory(directory.resolve("home"));
        Path auth = Files.writeString(home.resolve("auth.json"), "saved login");
        Path config = Files.writeString(home.resolve("config.toml"), "existing = true\n");

        try (CodexConnectorDriver ignored = factory().create(configuration(Mode.ACCOUNT), context(home))) {
            assertThat(auth).hasContent("saved login");
            assertThat(config).hasContent("existing = true\n");
        }
        assertThat(auth).hasContent("saved login");
        assertThat(config).hasContent("existing = true\n");
    }

    @Test
    void retainsTheLegacyAuthenticationPrecedence()
    {
        CodexConnectorConfig allModes = new CodexConnectorConfig(Optional.of(directory.resolve("never-run-codex")), Optional.of(EMAIL),
                Optional.of(gateway()), Optional.of(key()), Optional.of(key()));
        try (CodexConnectorDriver driver = factory().create(allModes, context(directory.resolve("deepseek")))) {
            assertThat(driver.label()).isEqualTo("DeepSeek");
        }
        CodexConnectorConfig gatewayFirst = new CodexConnectorConfig(allModes.executable(), allModes.account(), allModes.gateway(), Optional.empty(), allModes.apiKey());
        try (CodexConnectorDriver driver = factory().create(gatewayFirst, context(directory.resolve("gateway")))) {
            assertThat(driver.label()).isEqualTo("Codex gateway");
        }
        CodexConnectorConfig keyFirst = new CodexConnectorConfig(allModes.executable(), allModes.account(), Optional.empty(), Optional.empty(), allModes.apiKey());
        try (CodexConnectorDriver driver = factory().create(keyFirst, context(directory.resolve("key")))) {
            assertThat(driver.label()).isEqualTo("Codex · OpenAI API key");
        }
        assertThat(credentialReads).hasValue(0);
    }

    @Test
    void retainsAnInstallFailureWithoutProbingTheFallbackExecutable()
    {
        CodexConnectorConfig configuration = new CodexConnectorConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        String diagnostic = "This library records no Codex " + Codex.VERSION + " for " + CLASSIFIER + "; set Codex.builder().executable(path)";

        try (CodexConnectorDriver driver = factory().create(configuration, context(directory.resolve("home")))) {
            assertThat(driver.unavailable()).contains(diagnostic);
            assertThat(driver.testedVersion()).isEmpty();
            AgentProviderStatus status = driver.probe();
            assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.CLI_MISSING);
            assertThat(status.diagnostic()).isEqualTo(diagnostic);
            assertThat(status.version()).isEmpty();
            assertThat(driver.probe(Optional.of("model"))).isEqualTo(status);
        }
        assertThat(supervisor.closed).isFalse();
        assertThat(directory.resolve("cache")).doesNotExist();
    }

    @Test
    void keepsTheHomePreparationDiagnostic()
            throws IOException
    {
        Path home = Files.writeString(directory.resolve("not-a-directory"), "existing file");

        assertThatThrownBy(() -> factory().create(configuration(Mode.DEFAULT), context(home)))
                .isInstanceOf(AgentException.class)
                .hasMessage("Codex's home could not be prepared: " + home)
                .hasCauseInstanceOf(IOException.class);
        assertThat(home).hasContent("existing file");
        assertThat(supervisor.closed).isFalse();
    }

    private CodexConnector factory()
    {
        return new CodexConnector(new CodexBinary(directory.resolve("cache"), URI.create("http://127.0.0.1:1/"), CLASSIFIER, Map.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    private ConnectorContext context(Path home)
    {
        return new ConnectorContext(home, supervisor, new ToolServer("host_tools"));
    }

    private CodexConnectorConfig configuration(Mode mode)
    {
        return new CodexConnectorConfig(Optional.of(directory.resolve("never-run-codex")), mode == Mode.ACCOUNT ? Optional.of(EMAIL) : Optional.empty(),
                mode == Mode.GATEWAY ? Optional.of(gateway()) : Optional.empty(), mode == Mode.DEEPSEEK ? Optional.of(key()) : Optional.empty(),
                mode == Mode.API_KEY ? Optional.of(key()) : Optional.empty());
    }

    private Supplier<String> key()
    {
        return () -> {
            credentialReads.incrementAndGet();
            return "test-key";
        };
    }

    private CodexGateway gateway()
    {
        Supplier<String> token = key();
        return new CodexGateway("https://gateway.example/v1", () -> Optional.ofNullable(token.get()));
    }

    private AgentSessionContext sessionContext()
    {
        return new AgentSessionContext("task", directory, directory.resolve("runtime"), directory.resolve("runtime/provider"),
                AgentPermissionPolicy.AUTO, Optional.empty(), (request, timeout) -> { throw new AssertionError("Unexpected approval"); },
                Optional.empty(), Optional.empty(), SessionAccess.FULL, "");
    }

    private static String profile(Path home)
    {
        return Hashing.sha256().hashString(home.toString(), StandardCharsets.UTF_8).toString().substring(0, 12);
    }

    private enum Mode
    {
        DEFAULT,
        ACCOUNT,
        GATEWAY,
        API_KEY,
        DEEPSEEK
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

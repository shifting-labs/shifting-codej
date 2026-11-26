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
package io.github.shiftinglabs.codej.cli.antigravity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAntigravityPolicy
{
    @TempDir
    private Path directory;

    @Test
    void preparesPrivateCliConfigurationAndKeepsCredentialsOutOfSettings()
            throws Exception
    {
        Path home = directory.resolve("private");
        Map<String, String> env = AntigravityPolicy.environment(home, Map.of("GEMINI_API_KEY", "example-key", "HOME", "/ambient",
                "GOOGLE_GEMINI_BASE_URL", "https://other.invalid", "GITHUB_TOKEN", "other-secret"));
        assertThat(env).containsEntry("HOME", home.toRealPath().toString()).containsEntry("GEMINI_API_KEY", "example-key")
                .containsEntry("GEMINI_FORCE_FILE_STORAGE", "true").doesNotContainKeys("GOOGLE_GEMINI_BASE_URL", "GITHUB_TOKEN");
        assertThat(Files.getPosixFilePermissions(home)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        Path cli = home.resolve(".gemini/antigravity-cli");
        Path history = Files.writeString(cli.resolve("saved-conversation"), "keep");
        Path settings = cli.resolve("settings.json");
        var json = new ObjectMapper().readTree(settings.toFile());
        assertThat(json.path("modelProvider").asText()).isEqualTo("gemini");
        assertThat(json.path("permissions").path("deny").toString()).contains("command(*)", "read_url(*)", "execute_url(*)");
        assertThat(json.path("permissions").path("allow").toString()).contains("mcp(codej_session/*)");
        assertThat(json.toString()).doesNotContain("example-key", "other-secret");
        assertThat(Files.readString(home.resolve(".gemini/config/agents/codej-reader/agent.md")))
                .contains("inheritCustomizations: false", "inheritMcp: false", "tools:\n  - read_file\n---");
        assertThat(AntigravityPolicy.acpCommand("/bin/agy", context(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false))))
                .containsSubsequence("--agent", "codej-reader").contains("--include-default-customizations=false");
        AntigravityPolicy.environment(home, Map.of("GEMINI_API_KEY", "rotated"));
        assertThat(history).hasContent("keep");
        Path loginHome = directory.resolve("login");
        AntigravityPolicy.environment(loginHome, Map.of());
        assertThat(Files.readString(loginHome.resolve(".gemini/antigravity-cli/settings.json"))).doesNotContain("modelProvider");
    }

    @Test
    void rejectsProfileLinksAndCheckoutOverlapBeforeCreation()
            throws Exception
    {
        Path work = Files.createDirectory(directory.resolve("checkout"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), work);
        Path privateRoot = directory.resolve("profile");
        AntigravityPolicy.checkDirectories(privateRoot.resolve("login"), work);
        assertThat(privateRoot).doesNotExist();
        assertThatThrownBy(() -> AntigravityPolicy.checkDirectories(alias.resolve("missing/profile/login"), work))
                .isInstanceOf(AgentUnavailableException.class);
        assertThat(work.resolve("missing")).doesNotExist();
        assertThatThrownBy(() -> AntigravityPolicy.checkDirectories(directory, work)).isInstanceOf(AgentUnavailableException.class);
        Files.createDirectory(privateRoot);
        Files.createSymbolicLink(privateRoot.resolve(".gemini"), work);
        assertThatThrownBy(() -> AntigravityPolicy.environment(privateRoot, Map.of())).isInstanceOf(IOException.class);
        assertThat(work.resolve("antigravity-cli")).doesNotExist();
    }

    @Test
    void rejectsUnsupportedOptionsWithoutStartingOrConfiguringTheCli()
    {
        var baseline = new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false);
        AntigravityPolicy.validate(context(baseline));
        AntigravityPolicy.validate(context(baseline.withNoSubagents(true)));
        AntigravityPolicy.validate(context(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false)));
        for (var scope : List.of(new AgentPermissionScope(AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL, false),
                new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.TEXT_ONLY, false),
                baseline.withWebSearch(true), baseline.withNetwork(true), baseline.withWritableRoots(List.of(directory)),
                baseline.withSessionDenials(List.of(), List.of("/private/location")))) {
            assertThatThrownBy(() -> AntigravityPolicy.validate(context(scope))).isInstanceOf(AgentUnavailableException.class);
        }
        assertThat(directory.resolve("runtime")).doesNotExist();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void operatingSystemDeniesCheckoutWritesLinksAndOutsideReads()
            throws Exception
    {
        Path work = Files.createDirectory(directory.resolve("work"));
        Path home = directory.resolve("private");
        Map<String, String> env = AntigravityPolicy.environment(home, Map.of());
        Path inside = Files.writeString(work.resolve("inside"), "checkout");
        Path outside = Files.writeString(directory.resolve("outside"), "private");
        Files.createSymbolicLink(work.resolve("link"), outside);
        assertThat(run(work, home, env, List.of("/bin/cat", inside.toString()))).isZero();
        assertThat(run(work, home, env, List.of("/bin/cat", "link"))).isNotZero();
        assertThat(run(work, home, env, List.of("/bin/sh", "-c", "echo changed > inside"))).isNotZero();
        assertThat(run(work, home, env, List.of("/bin/sh", "-c", "echo changed > \"$HOME/.gemini/antigravity-cli/settings.json\""))).isNotZero();
        assertThat(run(work, home, env, List.of("/bin/sh", "-c", "echo changed > \"$HOME/.gemini/config/agents/codej-reader/agent.md\""))).isNotZero();
        assertThat(run(work, home, env, List.of("/bin/mv", home.resolve(".gemini/config").toString(), home.resolve("moved").toString()))).isNotZero();
        assertThat(run(work, home, env, List.of("/bin/sh", "-c", "echo log > \"$TMPDIR/log\""))).isZero();
        assertThat(inside).hasContent("checkout");
        assertThat(outside).hasContent("private");
    }

    private int run(Path work, Path home, Map<String, String> env, List<String> command)
            throws Exception
    {
        ProcessBuilder builder = new ProcessBuilder(AntigravityPolicy.command(command, home, work, SessionAccess.READ_ONLY)).directory(work.toFile());
        builder.environment().clear();
        builder.environment().putAll(env);
        return builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
    }

    private AgentSessionContext context(AgentPermissionScope scope)
    {
        return new AgentSessionContext("test", directory, directory.resolve("runtime"), directory.resolve("runtime/provider"), scope, Optional.empty(),
                (request, timeout) -> { throw new AssertionError("unexpected approval"); }, Optional.empty(), Optional.empty(), "", List.of(), false);
    }
}

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

/** Launch policy shared by Antigravity metadata probes and read-only ACP sessions. */
final class AntigravityPolicy
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SANDBOX = Path.of("/usr/bin/sandbox-exec");
    private static final String READER = """
            ---
            name: codej-reader
            description: Read checkout files for analysis
            mainAgent: true
            subagent: false
            model: inherit
            inheritMcp: false
            inheritCustomizations: false
            commandExecutionPolicy: off
            tools:
              - read_file
            ---
            Read files in the checkout to answer the user's request.
            """;

    // The provider itself needs its network connection and local session store. Its file tools get only the checkout.
    private static final String READ_ONLY_PROFILE = """
            (version 1)
            (allow default)
            (deny file-read-data)
            (allow file-read-data
                (subpath (param "WORKTREE")) (subpath (param "PROFILE")) (literal (param "EXECUTABLE"))
                (literal "/") (subpath "/System/Library") (subpath "/System/Cryptexes") (subpath "/usr/lib") (subpath "/usr/share")
                (subpath "/usr/bin") (subpath "/bin") (subpath "/sbin") (subpath "/dev")
                (subpath "/private/etc") (subpath "/Library/Preferences") (subpath "/private/var/run"))
            (deny file-write*)
            (allow file-write* (subpath (param "PROFILE")) (literal "/dev/null"))
            (deny file-write-unlink (literal (param "PROFILE")) (literal (param "GEMINI")) (literal (param "CLI")))
            (deny file-write* (literal (param "SETTINGS")) (subpath (param "CONFIG")))
            (deny file-link)
            (deny file-read* (regex #"/[._][aA][gG][eE][nN][tT][sS]?(/|$)" #"/[.]ssh(/|$)" #"/[.]config/gh(/|$)" #"/[.]git-credentials$" #"/[.]netrc$"))
            (deny mach-lookup (global-name "com.apple.securityd") (global-name "com.apple.securityd.xpc"))
            """;

    private AntigravityPolicy() {}

    static void validate(AgentSessionContext context)
    {
        requireNonNull(context, "context is null");
        var scope = context.scope();
        if (context.access() != SessionAccess.READ_ONLY || scope.policy() != AgentPermissionPolicy.AUTO) {
            throw new AgentUnavailableException("Antigravity CLI requires read-only checkout access");
        }
        if (!context.sessionTools().isEmpty() || context.asksUser() || !context.managedInstructions().isBlank() || !scope.standingInstructions().isBlank()) {
            throw new AgentUnavailableException("Antigravity CLI does not support host tools, user questions or managed instructions");
        }
        if (scope.network() || scope.webSearch() || scope.commits() ||
                !scope.writableRoots().isEmpty() || !scope.unsandboxedCommands().isEmpty() || !scope.unixSockets().isEmpty() ||
                !scope.reviewerRules().isEmpty() || !scope.reviewerContext().isEmpty() || scope.reviewSandboxedCommands() ||
                !scope.sessionDeniedReads().isEmpty() || !scope.sessionDeniedCommands().isEmpty()) {
            throw new AgentUnavailableException("Antigravity CLI does not support the requested permission scope");
        }
    }

    static Map<String, String> environment(Path home, Map<String, String> overrides)
            throws IOException
    {
        Path profile = privateDirectory(home.toAbsolutePath().normalize()).toRealPath();
        Path gemini = privateDirectory(profile.resolve(".gemini"));
        Path cli = privateDirectory(gemini.resolve("antigravity-cli"));
        Path config = privateDirectory(gemini.resolve("config"));
        privateDirectory(config.resolve("codej-sessions"));
        Path agents = privateDirectory(config.resolve("agents"));
        Path reader = privateDirectory(agents.resolve("codej-reader"));
        Path scratch = privateDirectory(profile.resolve("tmp"));
        ObjectNode settings = JSON.createObjectNode();
        settings.put("agentMode", "");
        settings.put("toolPermission", "request-review");
        settings.put("allowNonWorkspaceAccess", false);
        settings.put("enableTerminalSandbox", true);
        settings.putObject("permissions").putArray("deny")
                .add("write_file(*)").add("command(*)").add("mcp(*)").add("read_url(*)").add("execute_url(*)")
                .add("read_file(" + profile + ")");
        var environment = ImmutableMap.<String, String>builder()
                .put("HOME", profile.toString())
                .put("TMPDIR", scratch.toString())
                .put("PATH", overrides.getOrDefault("PATH", "/usr/bin:/bin"))
                .put("LANG", overrides.getOrDefault("LANG", "en_US.UTF-8"))
                .put("AGY_CLI_DISABLE_AUTO_UPDATE", "true")
                .put("GEMINI_FORCE_FILE_STORAGE", "true");
        String key = overrides.get("GEMINI_API_KEY");
        if (key != null) {
            settings.put("modelProvider", "gemini");
            environment.put("GEMINI_API_KEY", key);
        }
        writePolicy(reader.resolve("agent.md"), READER.getBytes(StandardCharsets.UTF_8));
        writePolicy(cli.resolve("settings.json"), JSON.writeValueAsBytes(settings));
        return environment.buildOrThrow();
    }

    private static void writePolicy(Path file, byte[] contents)
            throws IOException
    {
        // A running CLI only sees complete policies. Login state and conversations live in separate files.
        Path temporary = Files.createTempFile(file.getParent(), "codej-policy-", ".tmp");
        try {
            Files.write(temporary, contents);
            Files.move(temporary, file, ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    static List<String> acpCommand(String binary)
    {
        return List.of(binary, "acp", "--sandbox", "--agent", "codej-reader", "--include-default-customizations=false", "--disable-telemetry");
    }

    static List<String> command(List<String> argv, Path home, Path worktree)
            throws IOException
    {
        if (!Files.isExecutable(SANDBOX)) {
            throw new AgentUnavailableException("Antigravity CLI requires macOS Seatbelt for read-only checkout access");
        }
        if (argv.contains("acp")) {
            checkDirectories(home, worktree);
        }
        Path profile = home.toAbsolutePath().normalize().toRealPath();
        Path workspace = worktree.toRealPath();
        Path executable = Path.of(argv.getFirst());
        if (!executable.isAbsolute()) {
            throw new AgentUnavailableException("Antigravity CLI requires a resolved executable path");
        }
        Path gemini = profile.resolve(".gemini");
        Path cli = gemini.resolve("antigravity-cli");
        return ImmutableList.<String>builder()
                .add(SANDBOX.toString(), "-D", "PROFILE=" + profile, "-D", "WORKTREE=" + workspace,
                        "-D", "GEMINI=" + gemini, "-D", "CLI=" + cli, "-D", "CONFIG=" + gemini.resolve("config"),
                        "-D", "SETTINGS=" + cli.resolve("settings.json"), "-D", "EXECUTABLE=" + executable.toRealPath(),
                        "-p", READ_ONLY_PROFILE)
                .addAll(argv)
                .build();
    }

    static void checkDirectories(Path home, Path worktree)
            throws IOException
    {
        Path workspace = worktree.toRealPath();
        Path absolute = home.toAbsolutePath().normalize();
        Path parent = absolute;
        Path profile;
        while (true) {
            try {
                profile = parent.toRealPath().resolve(parent.relativize(absolute));
                break;
            }
            catch (NoSuchFileException e) {
                if (Files.isSymbolicLink(parent) || parent.getParent() == null) {
                    throw e;
                }
                parent = parent.getParent();
            }
        }
        if (profile.startsWith(workspace) || workspace.startsWith(profile)) {
            throw new AgentUnavailableException("Antigravity CLI needs its private profile outside the checkout");
        }
    }

    private static Path privateDirectory(Path path)
            throws IOException
    {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw new IOException("Antigravity profile directory is a symbolic link: " + path);
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }
}

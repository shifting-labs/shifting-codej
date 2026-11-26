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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
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
import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;

/** Launch policy shared by Antigravity metadata probes and ACP sessions. */
final class AntigravityPolicy
{
    static final String READER = "codej-reader";
    static final String TOOL_SERVER = "codej_session";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new Jdk8Module());
    private static final Path SANDBOX = Path.of("/usr/bin/sandbox-exec");
    private static final String AGENT = """
            ---
            name: %s
            description: CodeJ checkout agent
            mainAgent: true
            subagent: false
            model: inherit
            inheritMcp: false
            inheritCustomizations: false
            commandExecutionPolicy: off
            tools:
              - read_file
            %s---
            %s
            """;

    // The provider needs its own network connection and local session store. Only the writer gets checkout writes.
    private static final String PROFILE = """
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
            %s
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
        if (context.access() != SessionAccess.READ_ONLY && context.access() != SessionAccess.FULL || scope.policy() != AgentPermissionPolicy.AUTO) {
            throw new AgentUnavailableException("Antigravity CLI supports read-only and workspace-write access with the AUTO permission policy");
        }
        if (scope.network() || scope.webSearch() || scope.commits() ||
                !scope.writableRoots().isEmpty() || !scope.unsandboxedCommands().isEmpty() || !scope.unixSockets().isEmpty() ||
                !scope.reviewerRules().isEmpty() || !scope.reviewerContext().isEmpty() || scope.reviewSandboxedCommands() ||
                !scope.sessionDeniedReads().isEmpty() || !scope.sessionDeniedCommands().isEmpty() ||
                scope.review() || scope.publicationProtected() || scope.approveEveryCommand()) {
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
        Path scratch = privateDirectory(profile.resolve("tmp"));
        ToolPermissions permissions = new ToolPermissions(
                ImmutableList.of("command(*)", "read_url(*)", "execute_url(*)", "read_file(" + profile + ")", "write_file(" + profile + ")"),
                ImmutableList.of("mcp(" + TOOL_SERVER + "/*)"));
        var environment = ImmutableMap.<String, String>builder()
                .put("HOME", profile.toString())
                .put("TMPDIR", scratch.toString())
                .put("PATH", overrides.getOrDefault("PATH", "/usr/bin:/bin"))
                .put("LANG", overrides.getOrDefault("LANG", "en_US.UTF-8"))
                .put("AGY_CLI_DISABLE_AUTO_UPDATE", "true")
                .put("GEMINI_FORCE_FILE_STORAGE", "true");
        Optional<String> key = Optional.ofNullable(overrides.get("GEMINI_API_KEY"));
        key.ifPresent(value -> environment.put("GEMINI_API_KEY", value));
        CliSettings settings = new CliSettings("", "request-review", false, true, permissions, key.map(_ -> "gemini"));
        writeAgent(profile, READER, "", "Read files in the checkout to answer the user's request.");
        writePolicy(cli.resolve("settings.json"), JSON.writeValueAsBytes(settings));
        return environment.buildOrThrow();
    }

    static String agentName(AgentSessionContext context)
    {
        String role = context.restricted() ? READER : "codej-writer";
        return context.asksUser() ? role + "-questions" : role;
    }

    static void prepareAgent(Path home, AgentSessionContext context)
            throws IOException
    {
        String name = agentName(context);
        if (name.equals(READER)) {
            return;
        }
        String tools = context.restricted() ? "" : "  - write_file\n  - edit_file\n";
        if (context.asksUser()) {
            tools += "  - ask_question\n";
        }
        String role = context.restricted() ? "Read files in the checkout." : "Read and edit files in the checkout.";
        writeAgent(home.toRealPath(), name, tools, role);
    }

    private static void writeAgent(Path home, String name, String tools, String instructions)
            throws IOException
    {
        Path agents = privateDirectory(home.resolve(".gemini/config/agents"));
        Path agent = privateDirectory(agents.resolve(name));
        writePolicy(agent.resolve("agent.md"), AGENT.formatted(name, tools, instructions).getBytes(StandardCharsets.UTF_8));
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

    static List<String> acpCommand(String binary, AgentSessionContext context)
    {
        var command = ImmutableList.<String>builder()
                .add(binary, "acp", "--sandbox", "--agent", agentName(context), "--include-default-customizations=false", "--disable-telemetry");
        if (!context.sessionTools().isEmpty()) {
            // Antigravity only connects to editor-supplied HTTP MCP servers in this mode.
            command.add("--process-per-session");
        }
        return command.build();
    }

    static List<String> command(List<String> argv, Path home, Path worktree, SessionAccess access)
            throws IOException
    {
        if (!Files.isExecutable(SANDBOX)) {
            throw new AgentUnavailableException("Antigravity CLI requires macOS Seatbelt for checkout access");
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
        String workspaceWrites = access == SessionAccess.FULL ? """
                (allow file-write* (subpath (param "WORKTREE")))
                (deny file-write* (literal (param "WORKTREE")) (subpath (param "GIT_DIR")) (regex #"/[.][gG][iI][tT](/|$)"))
                """ : "";
        return ImmutableList.<String>builder()
                .add(SANDBOX.toString(), "-D", "PROFILE=" + profile, "-D", "WORKTREE=" + workspace,
                        "-D", "GIT_DIR=" + workspace.resolve(".git"), "-D", "GEMINI=" + gemini, "-D", "CLI=" + cli, "-D", "CONFIG=" + gemini.resolve("config"),
                        "-D", "SETTINGS=" + cli.resolve("settings.json"), "-D", "EXECUTABLE=" + executable.toRealPath(),
                        "-p", PROFILE.formatted(workspaceWrites))
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

    @JsonInclude(NON_ABSENT)
    private record CliSettings(String agentMode, String toolPermission, boolean allowNonWorkspaceAccess, boolean enableTerminalSandbox,
            ToolPermissions permissions, Optional<String> modelProvider)
    {
        private CliSettings
        {
            requireNonNull(agentMode, "agentMode is null");
            requireNonNull(toolPermission, "toolPermission is null");
            requireNonNull(permissions, "permissions is null");
            requireNonNull(modelProvider, "modelProvider is null");
        }
    }

    private record ToolPermissions(List<String> deny, List<String> allow)
    {
        private ToolPermissions
        {
            deny = ImmutableList.copyOf(deny);
            allow = ImmutableList.copyOf(allow);
        }
    }
}

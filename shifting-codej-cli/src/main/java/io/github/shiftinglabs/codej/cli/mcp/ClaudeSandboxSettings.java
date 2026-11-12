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
package io.github.shiftinglabs.codej.cli.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.GitWorktreeWriteRoots;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Builds Claude's sandbox settings and the command guidance that follows those settings. */
final class ClaudeSandboxSettings
{
    private static final List<String> CREDENTIAL_FILE_READS = ImmutableList.of("Read(~/.config/gh/**)", "Read(~/.git-credentials)", "Read(~/.netrc)", "Read(~/.ssh/**)");

    private final ObjectMapper objectMapper;
    private final ToolServer toolServer;

    ClaudeSandboxSettings(ObjectMapper objectMapper, ToolServer toolServer)
    {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.toolServer = requireNonNull(toolServer, "toolServer is null");
    }

    String settings(AgentPermissionScope scope, Path runtimeDirectory, Path worktree, Map<String, String> environment, Optional<Path> childGate)
            throws IOException
    {
        SessionAccess access = scope.access();
        List<String> denyRead = scope.deniedReads(environment);
        // git, gh and ssh read their own login files, so the sandbox may let them; the agent's Read tool never needs to.
        ImmutableSet.Builder<String> deny = ImmutableSet.<String>builder().addAll(CREDENTIAL_FILE_READS);
        denyRead.forEach(path -> deny.add("Read(" + path + ")", "Read(" + path + "/**)"));
        scope.deniedCommands().forEach(command -> deny.add("Bash(" + command + ")", "Bash(" + command + ":*)"));
        if (scope.readOnlySubagents()) {
            // This task's MCP token does not distinguish a root agent from a child caller, so a child is
            // admitted only behind the gate below, which keeps every write tool with the root.
            // Skills and workflows start other kinds of children; SendMessage can resume earlier children.
            deny.add("Skill", "Workflow", "SendMessage");
            if (childGate.isEmpty()) {
                deny.add("Agent", "Task");
            }
        }
        if (access == SessionAccess.TOOLS_ONLY || scope.noSubagents()) {
            // A tools-only session's child would inherit every host tool it holds, root-only ones included.
            deny.add("Agent", "Task", "Skill", "Workflow", "SendMessage");
        }
        boolean allowUnsandboxedRetry = access == SessionAccess.FULL && !scope.approveEveryCommand();
        ImmutableList.Builder<String> allowWrite = ImmutableList.builder();
        // The runtime directory holds CI logs, the event log, and these settings; the agent must not rewrite its evidence.
        ImmutableList.Builder<String> denyWrite = ImmutableList.<String>builder().add(runtimeDirectory.toString());
        allowWrite.addAll(scope.writableRoots().stream().map(Path::toString).collect(toImmutableList()));
        if (scope.commits() && Files.exists(worktree.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            List<String> gitRoots = GitWorktreeWriteRoots.writableRoots(worktree);
            allowWrite.addAll(gitRoots.subList(1, gitRoots.size()));
            Path gitDirectory = Path.of(gitRoots.get(1));
            GitWorktreeWriteRoots.PROTECTED_METADATA.forEach(name -> denyWrite.add(gitDirectory.resolve(name).toString()));
        }
        // A read-only session's Bash is auto-approved, so denying the worktree is its containment boundary.
        // A text-only worktree is already inside the runtime directory, and need not exist on disk.
        if (access == SessionAccess.READ_ONLY || access == SessionAccess.REVIEW_READ_ONLY || access == SessionAccess.TOOLS_ONLY) {
            denyWrite.add(worktree.toRealPath().toString());
        }
        SandboxFilesystem filesystem = new SandboxFilesystem(allowWrite.build(), denyRead, denyWrite.build());
        if (!scope.webSearch()) {
            deny.add("WebFetch", "WebSearch");
        }
        SandboxNetwork network = scope.network()
                ? new SandboxNetwork(ImmutableList.of("*"), Optional.of(true), Optional.of(scope.unixSockets()).filter(sockets -> !sockets.isEmpty()))
                : new SandboxNetwork(ImmutableList.of(), Optional.empty(), Optional.empty());
        Sandbox sandbox = new Sandbox(
                true,
                true,
                !scope.reviewSandboxedCommands() && !scope.approveEveryCommand(),
                // Native Auto mode reviews retries first; unresolved requests use the permission callback.
                allowUnsandboxedRetry,
                scope.unsandboxedCommands(),
                // gh and other Go tools verify TLS through macOS's trust agent; an open network loses nothing by reaching it.
                scope.network(),
                filesystem,
                network);
        // Native allow rules must not skip review, and native exceptions must not waive the session's rules.
        Optional<AutoMode> autoMode = scope.reviewerRules().isEmpty() ? Optional.empty() : Optional.of(new AutoMode(true,
                Optional.of(scope.reviewerContext()).filter(context -> !context.isEmpty())
                        .map(context -> ImmutableList.<String>builder().add("$defaults").addAll(context).build()),
                ImmutableList.<String>builder().add("$defaults").addAll(scope.reviewerRules()).build()));
        // A hook sees agent_id on a child's tool call; the MCP request itself carries nothing that identifies the caller.
        Optional<Hooks> hooks = childGate.map(gate -> new Hooks(ImmutableList.of(new HookMatcher(childGateMatcher(),
                ImmutableList.of(new CommandHook("command", "'" + gate.toString().replace("'", "'\\''") + "'"))))));
        return objectMapper.writeValueAsString(new ClaudeSettings(false, new Permissions(deny.build().asList()), sandbox, autoMode, hooks));
    }

    /**
     * How the agent must run the session's unsandboxed commands for Claude to exempt them; empty when every command stays sandboxed.
     * Claude exempts only the command alone, optionally after {@code cd <dir> &&}; a pipe, redirect, chain, or subshell keeps it in.
     */
    static String instruction(AgentPermissionScope scope)
    {
        if (scope.unsandboxedCommands().isEmpty()) {
            return "";
        }
        String commands = launchers(scope).map(command -> "`" + command + " ...`").collect(Collectors.joining(", "));
        return """
                These commands run outside the sandbox, where they can download dependencies, only when the Bash command is the
                call alone: %s, optionally after `cd <dir> &&`. A pipe, redirect, `;`, a later `&&`, a subshell or a leading
                variable assignment keeps it in the sandbox, where every download fails. For long output, run it quietly or in the
                background and read its output afterwards.
                """.formatted(commands);
    }

    /** A Bash command Claude runs outside its sandbox: one of the session's unsandboxed commands, alone. */
    static Pattern unsandboxedCommand(AgentPermissionScope scope)
    {
        if (scope.unsandboxedCommands().isEmpty()) {
            return Pattern.compile("(?!)");
        }
        return Pattern.compile("\\s*(cd\\s[^&]*&&\\s*)?(" + launchers(scope).map(Pattern::quote).collect(Collectors.joining("|")) + ")(\\s|$)");
    }

    private static Stream<String> launchers(AgentPermissionScope scope)
    {
        return scope.unsandboxedCommands().stream().map(pattern -> pattern.endsWith(" *") ? pattern.substring(0, pattern.length() - 2) : pattern);
    }

    /** Every call that starts a child, and every call to this server's tools, because a child inherits all of them. */
    private String childGateMatcher()
    {
        return "^(Agent|Task|" + toolServer.prefix() + ".*)$";
    }

    @JsonInclude(NON_ABSENT)
    private record ClaudeSettings(boolean autoMemoryEnabled, Permissions permissions, Sandbox sandbox, Optional<AutoMode> autoMode, Optional<Hooks> hooks) {}

    private record Permissions(List<String> deny) {}

    private record Sandbox(
            boolean enabled,
            boolean failIfUnavailable,
            boolean autoAllowBashIfSandboxed,
            boolean allowUnsandboxedCommands,
            List<String> excludedCommands,
            boolean enableWeakerNetworkIsolation,
            SandboxFilesystem filesystem,
            SandboxNetwork network) {}

    private record SandboxFilesystem(List<String> allowWrite, List<String> denyRead, List<String> denyWrite) {}

    @JsonInclude(NON_ABSENT)
    private record SandboxNetwork(List<String> allowedDomains, Optional<Boolean> allowLocalBinding, Optional<List<String>> allowUnixSockets) {}

    @JsonInclude(NON_ABSENT)
    private record AutoMode(boolean classifyAllShell, Optional<List<String>> environment, @JsonProperty("hard_deny") List<String> hardDeny) {}

    private record Hooks(@JsonProperty("PreToolUse") List<HookMatcher> preToolUse) {}

    private record HookMatcher(String matcher, List<CommandHook> hooks) {}

    private record CommandHook(String type, String command) {}
}

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
package io.github.shiftinglabs.codej.cli.codex;

import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

/** The one hook the SDK permits inside its isolated Codex home: it holds read-only children to the tools listed for them. */
final class CodexReviewGate
{
    static final String INSTRUCTION = """
            In this Codex session, subagents only read. A subagent may call the host tools on its MCP server, use native shell
            tools for read-only source searches, and use send_message to share findings with the main agent; the unqualified
            the server's call_tool belongs to the main agent. Discovery with its list_tools and get_tool does not expand
            this scope. Shell commands retain the native sandbox. Subagents must not edit files, run tests, commit, publish, save
            results or memories, request broader permissions, or delegate further work.
            Include this exploration scope in each child's self-contained assignment. Return conclusions, source references
            and missing evidence in your final answer; it is also delivered to the main agent automatically.
            If a call is denied, report the limitation instead of retrying it or working around the restriction.
            """;
    private static final String TEMPLATE = readScript();
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern FILE_NAME = Pattern.compile("codej-child-gate-([0-9a-f]{16})\\.sh");

    private CodexReviewGate() {}

    static String command(Path gate)
    {
        return "'" + requireNonNull(gate, "gate is null").toString().replace("'", "'\\''") + "'";
    }

    /** Installs the gate for these child tools under a name its content decides, so sessions with other tools never share it. */
    static Path install(ToolServer server, Path codexHome, List<String> childTools)
    {
        String script = script(server, childTools);
        Path target = requireNonNull(codexHome, "codexHome is null").resolve("codej-child-gate-" + digest(script) + ".sh");
        try {
            CodexHome.writeAtomically(target, script.getBytes(StandardCharsets.UTF_8), PosixFilePermissions.fromString("rwx------"));
            return target;
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to install Codex child gate", e);
        }
    }

    /** Checks that a gate still holds the script its name was given for. */
    static void verify(Path gate)
    {
        Matcher name = FILE_NAME.matcher(requireNonNull(gate, "gate is null").getFileName().toString());
        try {
            if (!name.matches()
                    || !Files.isRegularFile(gate, NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(gate, NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))
                    || !digest(Files.readString(gate, StandardCharsets.UTF_8)).equals(name.group(1))) {
                throw new AgentUnavailableException("Codex child gate changed after installation");
            }
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to verify Codex child gate", e);
        }
    }

    static String script(ToolServer server, List<String> childTools)
    {
        String names = childTools.stream()
                .filter(SAFE_NAME.asMatchPredicate())
                .flatMap(tool -> Stream.of(server.qualified(tool), tool))
                .collect(Collectors.joining("|"));
        // An empty case pattern is a shell syntax error; this one names no tool.
        return TEMPLATE.replace("@CHILD_TOOLS@", names.isEmpty() ? server.prefix() : names);
    }

    private static String digest(String script)
    {
        return Hashing.sha256().hashString(script, StandardCharsets.UTF_8).toString().substring(0, 16);
    }

    private static String readScript()
    {
        try {
            return Resources.toString(Resources.getResource(CodexReviewGate.class, "review-child-gate.sh"), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Unable to read Codex review child gate resource", e);
        }
    }
}

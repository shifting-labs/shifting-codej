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

import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared task MCP token needs a child-aware hook before review sessions may spawn agents. */
class TestCodexReviewProfile
{
    @TempDir
    Path codexHome;

    @Test
    void reviewSessionsKeepTheirModelChildProfileBehindTheGate()
    {
        for (CodexLaunchProfile.ChildProfile configured : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexCliDriver.childProfile(configured, false, true, "0.156.1"))
                    .as("a review configured for %s", configured)
                    .isEqualTo(configured);
            assertThat(CodexCliDriver.childProfile(configured, false, true, "0.155.9")).isEqualTo(CodexLaunchProfile.ChildProfile.NONE);
        }
        Path gate = CodexReviewGate.install(ToolServer.DEFAULT, codexHome, ImmutableList.of("review_get_context"));
        CodexLaunchProfile.ManagedToolsLaunch tools = new CodexLaunchProfile.ManagedToolsLaunch(
                URI.create("http://127.0.0.1:9000/mcp"), "Review", ImmutableList.of("review_get_context", "review_save_finding"),
                true, Optional.of(gate));
        for (CodexLaunchProfile.ChildProfile profile : ImmutableList.of(CodexLaunchProfile.ChildProfile.V1, CodexLaunchProfile.ChildProfile.V2)) {
            assertThat(CodexLaunchProfile.arguments(
                    "codex",
                    new CodexLaunchProfile.Options(profile, false, ImmutableList.of(), true, true, true, false, Optional.empty()),
                    Optional.of(tools)))
                    .contains("--enable", "hooks")
                    .anyMatch(argument -> argument.startsWith("hooks.PreToolUse=") && argument.contains(gate.toString()));
            assertThat(CodexLaunchProfile.expectedFeatures(profile, false, true, false)).containsEntry("hooks", true);
            assertThat(CodexLaunchProfile.threadConfig(new CodexLaunchProfile.Options(profile, false, ImmutableList.of(), true, true, true, false, Optional.empty()), true))
                    .containsEntry("bypass_hook_trust", true);
        }
        assertThat(CodexLaunchProfile.arguments(
                "codex",
                new CodexLaunchProfile.Options(CodexLaunchProfile.ChildProfile.NONE, false, ImmutableList.of(), true, true, true, false, Optional.empty()),
                Optional.of(tools)))
                .containsSequence("--disable", "multi_agent")
                .containsSequence("--disable", "multi_agent_v2")
                .doesNotContainSequence("--enable", "hooks");
        assertThat(CodexLaunchProfile.threadConfig(
                new CodexLaunchProfile.Options(CodexLaunchProfile.ChildProfile.NONE, false, ImmutableList.of(), true, true, true, false, Optional.empty()),
                true))
                .doesNotContainKey("bypass_hook_trust");
    }

    @Test
    void anOrdinaryTaskKeepsTheProfileItsModelAsksFor()
    {
        for (CodexLaunchProfile.ChildProfile configured : CodexLaunchProfile.ChildProfile.values()) {
            assertThat(CodexCliDriver.childProfile(configured, false, false, "0.156.1")).isEqualTo(configured);
            // A text-only task has no way to show child activity, so it keeps the existing opt-out.
            assertThat(CodexCliDriver.childProfile(configured, true, false, "0.156.1"))
                    .isEqualTo(CodexLaunchProfile.ChildProfile.NONE);
        }
    }

    @Test
    void childCanCallItsListedToolsExploreAndCommunicateButNothingElse()
            throws Exception
    {
        Path gate = CodexReviewGate.install(
                ToolServer.DEFAULT, codexHome, ImmutableList.of("review_get_context", "code_query", "repository_memory", "codej_list_tools", "codej_get_tool"));
        assertThat(runGate(gate, "{\"tool_name\":\"mcp__codej__review_save_finding\"}")).isZero();
        for (String tool : ImmutableList.of("review_get_context", "code_query", "repository_memory", "codej_list_tools")) {
            assertThat(runGate(gate, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__codej__" + tool + "\"}")).as("listed tool %s", tool).isZero();
        }
        assertThat(runGate(gate, "{\"agent_id\":\"child\",\"tool_name\":\"repository_memory\",\"tool_input\":{\"action\":\"SEARCH\"}}")).isZero();
        for (String tool : ImmutableList.of("Bash", "write_stdin")) {
            assertThat(runGate(gate, "{\"agent_id\":\"child\",\"tool_name\":\"" + tool + "\",\"tool_input\":{\"command\":\"rg -n decimal src\"}}"))
                    .as("child exploration tool %s", tool).isZero();
        }
        for (String target : ImmutableList.of("/root", "root-id", "/root/sibling", "unknown")) {
            assertThat(runGate(gate, """
                    {"session_id":"root-id","agent_id":"child","tool_name":"collaborationsend_message","tool_input":{"target":"%s","message":"Findings"}}
                    """.formatted(target))).isEqualTo(target.equals("/root") || target.equals("root-id") ? 0 : 2);
        }
        for (String tool : ImmutableList.of(
                "mcp__codej__review_save_finding", "mcp__codej__repository_memory_save", "repository_memory_save", "mcp__codej__codej_call_tool",
                "apply_patch", "Agent", "collaborationspawn_agent")) {
            assertThat(runGate(gate, "{\"agent_id\":\"child\",\"tool_name\":\"" + tool + "\"}"))
                    .as("child tool %s", tool)
                    .isEqualTo(2);
        }
        assertThat(runGate(gate, "{\"agent_id\":\"child\"}")).isEqualTo(2);
    }

    @Test
    void eachChildToolListGetsItsOwnGateAndAChangedGateFailsItsCheck()
            throws Exception
    {
        Path review = CodexReviewGate.install(ToolServer.DEFAULT, codexHome, ImmutableList.of("review_get_context"));
        Path other = CodexReviewGate.install(ToolServer.DEFAULT, codexHome, ImmutableList.of("code_query"));
        assertThat(other).isNotEqualTo(review);
        assertThat(CodexReviewGate.install(ToolServer.DEFAULT, codexHome, ImmutableList.of("review_get_context"))).isEqualTo(review);
        assertThat(runGate(review, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__codej__code_query\"}")).isEqualTo(2);
        CodexReviewGate.verify(review);
        Files.writeString(review, Files.readString(review).replace("exit 2", "exit 0"));
        assertThatThrownBy(() -> CodexReviewGate.verify(review)).hasMessage("Codex child gate changed after installation");
    }

    @Test
    void childDenialExplainsHowToReturnResultsWithoutAnotherToolCall()
            throws Exception
    {
        Process process = new ProcessBuilder(CodexReviewGate.install(ToolServer.DEFAULT, codexHome, ImmutableList.of("review_get_context")).toString()).start();
        process.getOutputStream().write("{\"agent_id\":\"child\",\"tool_name\":\"repository_memory_save\"}".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String denial = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).isEqualTo(2);
        assertThat(denial).contains("in your final answer", "tools listed for subagents", "messages to the main agent", "do not retry denied calls");
        assertThat(CodexAppServerClient.diagnosticLevel("codex_core::tools::router: error=Tool call blocked by PreToolUse hook: " + denial)).isEqualTo(Level.DEBUG);
    }

    @Test
    void gateCommandRunsThroughShellWhenProfilePathContainsSpacesAndApostrophe()
            throws Exception
    {
        Path home = Files.createDirectory(codexHome.resolve("profile with Sam's name"));
        Path gate = CodexReviewGate.install(ToolServer.DEFAULT, home, ImmutableList.of("review_get_context"));
        Process process = new ProcessBuilder("/bin/sh", "-c", CodexReviewGate.command(gate)).start();
        process.getOutputStream().write("{\"tool_name\":\"mcp__codej__review_save_finding\"}".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertThat(process.waitFor()).isZero();
    }

    private static int runGate(Path gate, String payload)
            throws Exception
    {
        Process process = new ProcessBuilder(gate.toString()).start();
        process.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        return process.waitFor();
    }
}

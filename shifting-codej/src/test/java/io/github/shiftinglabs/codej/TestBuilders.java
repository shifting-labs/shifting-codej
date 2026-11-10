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
package io.github.shiftinglabs.codej;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public builders over real CLI processes, played by small scripts. */
class TestBuilders
{
    @TempDir
    private Path folder;

    @Test
    void codexStatusReadsTheCliItRuns()
            throws IOException
    {
        try (Agent codex = Codex.builder().executable(codex("0.164.0")).stateDirectory(folder.resolve("state")).build()) {
            AgentStatus status = codex.status();
            assertThat(status.readiness()).as(status.detail()).isEqualTo(AgentStatus.Readiness.READY);
            assertThat(status.version()).contains("0.164.0");
            assertThat(status.capabilities().steering()).isTrue();
        }
        try (Agent old = Codex.builder().executable(codex("0.100.0")).stateDirectory(folder.resolve("state")).build()) {
            assertThat(old.status().readiness()).isEqualTo(AgentStatus.Readiness.UNSUPPORTED_VERSION);
        }
        try (Agent missing = Codex.builder().executable(folder.resolve("no-codex")).stateDirectory(folder.resolve("state")).build()) {
            assertThat(missing.status().readiness()).isEqualTo(AgentStatus.Readiness.CLI_MISSING);
        }
        assertThat(Files.isDirectory(folder.resolve("state/codex"))).isTrue();
        assertThat(launchRecords()).isEmpty();
    }

    @Test
    void codexNotesANewerExecutableIsUntested()
            throws IOException
    {
        try (Agent codex = Codex.builder().executable(codex("0.170.0")).stateDirectory(folder.resolve("state")).build()) {
            AgentStatus status = codex.status();
            assertThat(status.readiness()).isEqualTo(AgentStatus.Readiness.READY);
            assertThat(status.detail()).endsWith("This library is tested with Codex " + Codex.VERSION + "; 0.170.0 is newer and untested.");
        }
    }

    @Test
    void buildersRefuseTwoLogins()
    {
        assertThatThrownBy(() -> Codex.builder().account("me@example.com").gateway(URI.create("https://llm.example.com/v1"), () -> "token").build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Claude.builder().account("me@example.com").gateway(URI.create("https://llm.example.com"), () -> "token").build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void claudeRunsATurnAndStopsItsProcess()
            throws Exception
    {
        try (Agent claude = Claude.builder().executable(claude()).stateDirectory(folder.resolve("state")).build()) {
            AgentStatus status = claude.status();
            assertThat(status.readiness()).as(status.detail()).isEqualTo(AgentStatus.Readiness.READY);

            Path checkout = Files.createDirectories(folder.resolve("checkout"));
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            try (Session session = claude.startSession(SessionOptions.in(checkout).build())) {
                TurnResult result = session.start(Input.text("Say hello"), events::add).await();
                assertThat(result).isInstanceOfSatisfying(TurnResult.Completed.class, completed -> {
                    assertThat(completed.finalText()).isEqualTo("Hello from Claude");
                    assertThat(completed.usage().outputTokens()).isEqualTo(3);
                });
                assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOf(AgentEvent.Message.class));
                SessionRef ref = session.ref().orElseThrow();
                assertThat(SessionRef.parse(ref.asString())).isEqualTo(ref);
                assertThat(ref.provider()).isEqualTo("claude");
            }
            assertThat(folder.resolve("state/sessions")).isEmptyDirectory();
        }
        assertThat(launchRecords()).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void claudeCallsTheSessionsOwnTool()
            throws Exception
    {
        List<String> calls = new CopyOnWriteArrayList<>();
        Tool echo = Tool.withSchema("echo", "Repeats its text", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}", arguments -> {
            calls.add(arguments);
            return "echoed";
        });
        try (Agent claude = Claude.builder().executable(claude()).stateDirectory(folder.resolve("state")).build();
                Session session = claude.startSession(SessionOptions.in(Files.createDirectories(folder.resolve("assistant"))).access(Access.TOOLS_ONLY).tool(echo).build())) {
            TurnResult result = session.start(Input.text("call echo"), _ -> {}).await();
            assertThat(result).isInstanceOfSatisfying(TurnResult.Completed.class, completed -> assertThat(completed.finalText()).contains("echoed"));
        }
        assertThat(calls).containsExactly("{\"text\":\"hi\"}");
    }

    private List<Path> launchRecords()
            throws IOException
    {
        try (Stream<Path> files = Files.list(folder.resolve("state/processes/intents"))) {
            return files.toList();
        }
    }

    private Path codex(String version)
            throws IOException
    {
        return script("codex-" + version, """
                #!/bin/bash
                case "$1" in
                  --version) echo "codex-cli %s"; exit 0 ;;
                  login) echo "Logged in using ChatGPT"; exit 0 ;;
                esac
                exit 1
                """.formatted(version));
    }

    private Path claude()
            throws IOException
    {
        return script("claude", """
                #!/bin/bash
                case "$1" in
                  --version) printf '2.1.263 (Claude Code)\\n'; exit 0 ;;
                  --help)
                    printf '%s\\n' '--output-format --include-partial-messages --include-hook-events --forward-subagent-text'
                    printf '%s\\n' '--permission-mode (choices: "auto" "manual") --mcp-config --settings --setting-sources --strict-mcp-config'
                    printf '%s\\n' '--add-dir --append-system-prompt --tools --no-session-persistence --disable-slash-commands --model --effort'
                    printf '%s\\n' '--allowedTools --permission-prompt-tool --print --verbose --resume --session-id'
                    exit 0 ;;
                  auth) printf '{"loggedIn":true}\\n'; exit 0 ;;
                  auto-mode) printf '{"allow":[],"hard_deny":[]}\\n'; exit 0 ;;
                esac
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    --session-id|--resume) session_id="$2"; shift ;;
                    --mcp-config) mcp_config="$2"; shift ;;
                  esac
                  shift
                done
                IFS= read -r prompt
                answer="Hello from Claude"
                if [ "$prompt" = "call echo" ]; then
                  # Call the session's tool the way Claude's MCP client does: the URL and bearer from its MCP config.
                  url=$(sed -n 's/.*"url":"\\([^"]*\\)".*/\\1/p' "$mcp_config")
                  bearer=$(sed -n 's/.*"Authorization":"\\([^"]*\\)".*/\\1/p' "$mcp_config")
                  reply=$(curl -s -X POST "$url" -H "Authorization: $bearer" -H 'Content-Type: application/json' \\
                    -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}')
                  answer=$(printf '%s' "$reply" | sed -n 's/.*"text":"\\([^"]*\\)".*/\\1/p')
                fi
                printf '{"type":"system","subtype":"init","session_id":"%s","uuid":"init"}\\n' "$session_id"
                printf '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"%s"}]},' "$answer"
                printf '"session_id":"%s","uuid":"answer"}\\n' "$session_id"
                printf '{"type":"result","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"session_id":"%s",' "$session_id"
                printf '"usage":{"input_tokens":5,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":3},'
                printf '"subtype":"success","result":"%s","uuid":"result"}\\n' "$answer"
                """);
    }

    private Path script(String name, String text)
            throws IOException
    {
        Path script = Files.writeString(folder.resolve(name), text);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}

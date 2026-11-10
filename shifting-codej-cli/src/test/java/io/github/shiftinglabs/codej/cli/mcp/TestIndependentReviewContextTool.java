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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ReviewTool;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TestIndependentReviewContextTool
{
    @TempDir
    private Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));

    @Test
    void contextReadersAreSessionBoundReadOnlyAndRevokedOnClose()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(mapper);
        String closed;
        try (McpRegistration codex = broker.registerTools("same-task", directory, new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false, false),
                approvals, redactor, ImmutableList.of(context("first known-secret"))).orElseThrow();
                McpRegistration claude = broker.register("same-task", directory, directory, directory.resolve("provider"),
                        new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false, false), approvals, redactor, ImmutableMap.of(),
                        ImmutableList.of(context("second")))) {
            closed = codex.token();
            assertThat(codex.toolNames()).containsExactly("review_get_context", "codej_list_tools", "codej_get_tool", "codej_call_tool");
            assertThat(claude.toolNames()).isEqualTo(codex.toolNames());
            assertThat(call(broker, codex.token(), "review_get_context", "{}").at("/result/content/0/text").asText())
                    .startsWith("first ").doesNotContain("known-secret", "second");
            assertThat(call(broker, claude.token(), "review_get_context", "{}").at("/result/content/0/text").asText()).isEqualTo("second");
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(call(broker, token, "review_save_finding", "{}").has("error")).isTrue();
                assertThat(call(broker, token, "codej_call_tool", "{\"name\":\"review_get_context\",\"arguments\":{}}")
                        .at("/result/isError").asBoolean()).isFalse();
            }
            JsonNode settings = mapper.readTree(Files.readString(claude.settingsFile().orElseThrow()));
            assertThat(settings.at("/sandbox/filesystem/allowWrite")).isEmpty();
            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        }
        assertThat(broker.handle(closed, mapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void anAgendaRunReadsItsDayThroughTheSameSlot()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(mapper);
        Tool agenda = ReviewTool.of("agenda_context", "Read the user's day", ImmutableMap.of("type", "object"), query -> "today").forSubagents();
        try (McpRegistration codex = broker.registerTools("agenda-run", directory, new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false, false),
                approvals, redactor, ImmutableList.of(agenda)).orElseThrow()) {
            assertThat(codex.toolNames()).containsExactly("agenda_context", "codej_list_tools", "codej_get_tool", "codej_call_tool");
            assertThat(call(broker, codex.token(), "agenda_context", "{}").at("/result/content/0/text").asText()).isEqualTo("today");
        }
    }

    @Test
    void aToolsOnlySessionCarriesOnlyTheAssistantToolsAndApprovesNothing()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(mapper);
        AgentApprovalHandler noCards = (request, timeout) -> { throw new AssertionError("A tools-only session has nothing to approve"); };
        List<String> acted = new CopyOnWriteArrayList<>();
        Tool read = ReviewTool.of("assistant_context", "Read the user's day", ImmutableMap.of("type", "object"), query -> "today").forSubagents();
        Tool act = ReviewTool.of("assistant_act", "Act on the user's words", ImmutableMap.of("type", "object"), query -> {
            acted.add(query);
            return "done";
        });
        Tool show = ReviewTool.of("assistant_show", "Show the user a list", ImmutableMap.of("type", "object"), query -> "shown");
        Path day = Files.createDirectories(directory.resolve("assistant/2026-10-17"));
        try (McpRegistration codex = broker.registerTools("assistant-run", day, new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.TOOLS_ONLY, false, false),
                noCards, redactor, ImmutableList.of(read, act, show)).orElseThrow();
                McpRegistration claude = broker.register("assistant-run", day, directory, directory.resolve("provider"),
                        new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.TOOLS_ONLY, false, false), noCards, redactor, ImmutableMap.of(),
                        ImmutableList.of(read, act, show))) {
            // It calls its three tools by name: no catalog, and no second way to reach assistant_act.
            assertThat(codex.toolNames()).containsExactly("assistant_context", "assistant_act", "assistant_show");
            assertThat(claude.toolNames()).isEqualTo(codex.toolNames());
            assertThat(codex.instruction()).isEmpty();
            assertThat(claude.instruction()).isEmpty();
            assertThat(call(broker, codex.token(), "assistant_context", "{\"kind\":\"overview\"}").at("/result/content/0/text").asText()).isEqualTo("today");
            assertThat(call(broker, claude.token(), "assistant_act", "{\"op\":\"reply_to_task\"}").at("/result/content/0/text").asText()).isEqualTo("done");
            assertThat(call(broker, codex.token(), "assistant_show", "{\"op\":\"list\"}").at("/result/content/0/text").asText()).isEqualTo("shown");
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(call(broker, token, "codej_list_tools", "{}").at("/error/message").asText()).isEqualTo("Unknown tool");
                assertThat(call(broker, token, "codej_get_tool", "{\"name\":\"assistant_act\"}").at("/error/message").asText()).isEqualTo("Unknown tool");
                assertThat(call(broker, token, "codej_call_tool", "{\"name\":\"assistant_act\",\"arguments\":{}}").at("/error/message").asText()).isEqualTo("Unknown tool");
            }
            JsonNode refused = mapper.readTree(call(broker, claude.token(), "permission_prompt", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"ls\"}}")
                    .at("/result/content/0/text").asText());
            assertThat(refused.path("behavior").asText()).isEqualTo("deny");
            assertThat(refused.path("message").asText()).isEqualTo("This session has nothing to approve");
            JsonNode settings = mapper.readTree(Files.readString(claude.settingsFile().orElseThrow()));
            assertThat(settings.at("/permissions/deny").toString()).contains("\"Agent\"", "\"Task\"", "\"Skill\"", "\"Workflow\"", "\"SendMessage\"", "\"WebSearch\"");
            assertThat(settings.at("/sandbox/filesystem/denyWrite").toString()).contains(day.toRealPath().toString());
            assertThat(settings.has("hooks")).isFalse();
        }
        assertThat(acted).containsExactly("{\"op\":\"reply_to_task\"}");
    }

    private static Tool context(String text)
    {
        return ReviewTool.of("review_get_context", "Read captured context", ImmutableMap.of("type", "object"), query -> text).forSubagents();
    }

    private JsonNode call(PermissionBroker broker, String token, String name, String arguments)
            throws Exception
    {
        return broker.handle(token, mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(name, arguments))).body().orElseThrow();
    }
}

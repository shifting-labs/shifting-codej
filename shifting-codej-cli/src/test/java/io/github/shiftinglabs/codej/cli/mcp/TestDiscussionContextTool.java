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
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.DiscussionContextBridge;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TestDiscussionContextTool
{
    @TempDir
    private Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));

    @Test
    void bothProvidersServeTheSessionsOwnReadAndRejectCrossDiscussionOrWriteArguments()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(mapper);
        String closed;
        try (McpRegistration codex = broker.registerTools(
                "same-task",
                directory,
                new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false, false),
                approvals,
                redactor,
                ImmutableList.of(new DiscussionContextBridge(query -> "first known-secret").tool())).orElseThrow();
                McpRegistration claude = broker.register("same-task", directory, directory, directory.resolve("provider"),
                        new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false, false), approvals, redactor, ImmutableMap.of(),
                        ImmutableList.of(new DiscussionContextBridge(query -> "second").tool()))) {
            closed = codex.token();
            assertThat(codex.toolNames()).containsExactly(DiscussionContextBridge.TOOL_NAME, "codej_list_tools", "codej_get_tool", "codej_call_tool");
            assertThat(claude.toolNames()).isEqualTo(codex.toolNames());
            assertThat(call(broker, codex.token(), "read_discussion_context", "{\"section\":\"history\"}").at("/result/content/0/text").asText())
                    .startsWith("first ").doesNotContain("known-secret", "second");
            assertThat(call(broker, claude.token(), "read_discussion_context", "{\"section\":\"history\",\"offset\":0}").at("/result/content/0/text").asText()).isEqualTo("second");
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                for (String invalid : ImmutableList.of("{}", "{\"section\":\"other-task\"}", "{\"section\":\"history\",\"taskId\":\"other\"}",
                        "{\"section\":\"history\",\"offset\":-1}", "{\"section\":\"history\",\"offset\":0.5}",
                        "{\"section\":\"history\",\"offset\":2147483648}", "{\"section\":\"history\",\"offset\":0,\"discussionId\":\"other\"}")) {
                    assertThat(call(broker, token, "read_discussion_context", invalid).at("/result/isError").asBoolean()).as(invalid).isTrue();
                }
                assertThat(call(broker, token, "resolve_local_review_comment", "{}").has("error")).isTrue();
                assertThat(call(broker, token, "codej_call_tool", "{\"name\":\"read_discussion_context\",\"arguments\":{\"section\":\"source\"}}")
                        .at("/result/isError").asBoolean()).isFalse();
            }
            JsonNode settings = mapper.readTree(Files.readString(claude.settingsFile().orElseThrow()));
            assertThat(settings.at("/sandbox/filesystem/allowWrite")).isEmpty();
            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        }
        assertThat(broker.handle(closed, mapper.createObjectNode()).status()).isEqualTo(401);
        assertThat(broker.registerTools(
                "same-task",
                directory,
                new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false, false),
                approvals,
                redactor,
                ImmutableList.of())).isEmpty();
    }

    private JsonNode call(PermissionBroker broker, String token, String name, String arguments)
            throws Exception
    {
        return broker.handle(token, mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(name, arguments))).body().orElseThrow();
    }
}

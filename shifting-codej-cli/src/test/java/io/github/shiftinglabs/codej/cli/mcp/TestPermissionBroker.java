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
import io.github.shiftinglabs.codej.cli.TaskScopes;
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
import io.github.shiftinglabs.codej.cli.driver.AgentCommandPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.CodeQueryBridge;
import io.github.shiftinglabs.codej.cli.driver.IssueContextBridge;
import io.github.shiftinglabs.codej.cli.driver.LocalReviewBridge;
import io.github.shiftinglabs.codej.cli.driver.MemoryBridge;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalScope;
import io.github.shiftinglabs.codej.cli.driver.RelatedWorkspaceBridge;
import io.github.shiftinglabs.codej.cli.driver.ReviewBridge;
import io.github.shiftinglabs.codej.cli.driver.ReviewTool;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPermissionBroker
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void freshNativeAuthorityCannotBeUsedThroughHttpAndIsRevokedOnRelease()
            throws Exception
    {
        AtomicInteger writes = new AtomicInteger();
        PermissionBroker broker = new PermissionBroker(objectMapper);
        McpRegistration current = registerCodexTask(broker, "task", SecretRedactor.fromEnvironment(ImmutableMap.of()), rootTool(arguments -> {
            writes.incrementAndGet();
            return "linked";
        }));
        broker.nativeOnly(current);
        JsonNode call = objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"codej_call_tool","arguments":{"name":"related_workspace_work","arguments":{}}}}
                """);
        assertThat(broker.handle(current.token(), call).status()).isEqualTo(401);
        assertThat(writes).hasValue(0);
        assertThat(rootToolCall(broker, current.token(), "related_workspace_work", "{}").at("/result/content/0/text").asText()).isEqualTo("linked");
        assertThat(writes).hasValue(1);
        current.close();
        assertThat(broker.handle(current.token(), call).status()).isEqualTo(401);
        assertThat(rootToolCall(broker, current.token(), "related_workspace_work", "{}").has("error")).isTrue();
        assertThat(writes).hasValue(1);
    }

    @Test
    void providersExposeAndApproveTheSameTaskTools()
            throws Exception
    {
        ReviewBridge review = new ReviewBridge(ImmutableList.of(ReviewTool.of(
                "review_extra_evidence", "Read more evidence", ImmutableMap.of("type", "object"), arguments -> "evidence")), "Review this PR");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        for (String taskId : ImmutableList.of("plain", "review", "graph", "ci")) {
            AgentPermissionPolicy policy = taskId.equals("ci") ? AgentPermissionPolicy.CI_AUTOFIX : AgentPermissionPolicy.AUTO;
            Optional<CodeQueryBridge> graph = taskId.equals("graph") ? Optional.of(new CodeQueryBridge(query -> "graph", "Use the graph")) : Optional.empty();
            List<Tool> sessionTools = taskId.equals("review") ? review.tools() : graph.map(bridge -> ImmutableList.of(bridge.tool())).orElse(ImmutableList.of());
            try (McpRegistration registration = broker.register(taskId, temporaryDirectory, temporaryDirectory, temporaryDirectory, new AgentPermissionScope(policy, SessionAccess.FULL, taskId.equals("review"), false),
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), sessionTools)) {
                JsonNode listed = listTools(broker, registration.token());
                assertThat(registration.toolNames()).as(taskId)
                        .containsExactlyElementsOf(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList().stream()
                                .filter(name -> !name.equals("permission_prompt"))
                                .toList())
                        .doesNotContain("permission_prompt");
                if (taskId.equals("review")) {
                    assertThat(registration.toolNames()).containsExactly("review_extra_evidence", "codej_list_tools", "codej_get_tool", ToolServer.DEFAULT.callTool());
                }
                Optional<McpRegistration> codex = broker.registerTools(
                        taskId,
                        temporaryDirectory,
                        new AgentPermissionScope(policy, SessionAccess.FULL, taskId.equals("review"), false),
                        (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                        SecretRedactor.fromEnvironment(ImmutableMap.of()),
                        sessionTools);
                // A plain task and a CI repair get their tools from the host application's catalog, which this broker test leaves out.
                if (taskId.equals("plain") || taskId.equals("ci")) {
                    assertThat(codex).isEmpty();
                }
                else {
                    try (McpRegistration tools = codex.orElseThrow()) {
                        JsonNode codexListed = listTools(broker, tools.token());
                        assertThat(codexListed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                                .as(taskId).containsExactlyElementsOf(registration.toolNames());
                        assertThat(tools.instruction()).isEqualTo(registration.instruction());
                    }
                }
            }
        }
    }

    @Test
    void toolsDigestFollowsTheToolsAndNotTheSession()
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Function<String, String> digest = taskId -> {
            List<Tool> evidence = ImmutableList.of(ReviewTool.of(
                    "review_extra_evidence", taskId.equals("changed") ? "Read more of the evidence" : "Read more evidence", ImmutableMap.of("type", "object"), arguments -> "evidence"));
            try (McpRegistration tools = McpTestSupport.registerReviewCodex(
                    broker, taskId, temporaryDirectory, AgentPermissionPolicy.AUTO, SecretRedactor.fromEnvironment(ImmutableMap.of()), evidence).orElseThrow()) {
                return tools.toolsDigest();
            }
        };
        assertThat(digest.apply("first")).matches("[0-9a-f]{64}").isEqualTo(digest.apply("second"));
        assertThat(digest.apply("changed")).isNotEqualTo(digest.apply("first"));
    }

    @Test
    void anIssueBoundSessionServesItsTaskToolsToBothProvidersAndRefusesPushes()
            throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        Tool issue = Tool.withSchema(IssueContextBridge.TOOL_NAME, "Read this task's local source context",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", arguments -> {
                    reads.incrementAndGet();
                    return "Accepted source known-secret";
                }).forSubagents();
        PermissionBroker broker = new PermissionBroker(objectMapper);
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        AgentPermissionScope issueBound = TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, true);
        String codexToken;
        String claudeToken;
        try (McpRegistration codex = broker.registerTools("task-1", temporaryDirectory, issueBound, approvals, redactor,
                ImmutableList.of(issue)).orElseThrow();
                McpRegistration claude = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, issueBound, approvals, redactor, ImmutableMap.of(), ImmutableList.of(issue))) {
            codexToken = codex.token();
            claudeToken = claude.token();
            assertThat(claude.toolNames()).containsExactly(IssueContextBridge.TOOL_NAME, "codej_list_tools", "codej_get_tool", ToolServer.DEFAULT.callTool());
            // A tool subagents may call needs no proof of who called it.
            assertThat(codex.rootTools() || claude.rootTools()).isFalse();
            // The host application publishes an issue-bound task, so the session cannot push or use gh itself.
            assertThat(Files.readString(claude.settingsFile().orElseThrow())).contains("Bash(git push:*)", "Bash(gh:*)");
            assertThat(reads).hasValue(0);
            for (String token : ImmutableList.of(codexToken, claudeToken)) {
                JsonNode definition = listTools(broker, token).at("/result/tools").findParents("name").stream()
                        .filter(tool -> tool.path("name").asText().equals(IssueContextBridge.TOOL_NAME)).findFirst().orElseThrow();
                assertThat(definition.at("/inputSchema/properties").isEmpty()).isTrue();
                assertThat(definition.at("/inputSchema/additionalProperties").booleanValue()).isFalse();
                JsonNode result = localReviewCall(broker, token, IssueContextBridge.TOOL_NAME, "{}");
                assertThat(result.at("/result/isError").booleanValue()).isFalse();
                assertThat(result.at("/result/content/0/text").textValue()).isEqualTo("Accepted source [REDACTED]");
            }
            assertThat(reads).hasValue(2);
        }
        assertThat(broker.handle(codexToken, objectMapper.readTree("{}")).status()).isEqualTo(401);
        assertThat(broker.handle(claudeToken, objectMapper.readTree("{}")).status()).isEqualTo(401);
    }

    @Test
    void taskToolsReceiveArgumentsAndAnswerWithoutTheProvidersCredentials()
            throws Exception
    {
        List<String> received = new ArrayList<>();
        Tool reply = Tool.withSchema(LocalReviewBridge.REPLY_TOOL, "Reply privately", "{\"type\":\"object\"}", arguments -> {
            received.add(arguments);
            if (arguments.contains("foreign")) {
                throw new IllegalArgumentException("Comment belongs to another task known-secret");
            }
            return "saved known-secret";
        }).forSubagents();
        PermissionBroker broker = new PermissionBroker(objectMapper);
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        AgentPermissionScope scope = TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false);
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        try (McpRegistration codex = broker.registerTools("task-1", temporaryDirectory, scope, approvals, redactor,
                ImmutableList.of(reply)).orElseThrow();
                McpRegistration claude = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, scope, approvals, redactor, ImmutableMap.of(), ImmutableList.of(reply))) {
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(listTools(broker, token).at("/result/tools").findValues("name").stream().map(JsonNode::asText).toList())
                        .contains(LocalReviewBridge.REPLY_TOOL);
                JsonNode saved = localReviewCall(broker, token, LocalReviewBridge.REPLY_TOOL, """
                        {"commentId":"comment-1","body":"Answer known-secret","quotes":["known-secret",7],"nested":{"line":"see known-secret"},"retry":true}
                        """);
                assertThat(saved.at("/result/isError").booleanValue()).isFalse();
                assertThat(saved.at("/result/content/0/text").textValue()).isEqualTo("saved [REDACTED]");
                JsonNode foreign = localReviewCall(broker, token, LocalReviewBridge.REPLY_TOOL, "{\"commentId\":\"foreign\"}");
                assertThat(foreign.at("/result/isError").booleanValue()).isTrue();
                assertThat(foreign.at("/result/content/0/text").textValue()).isEqualTo("Comment belongs to another task [REDACTED]");
            }
        }
        assertThat(received).hasSize(4).allMatch(arguments -> !arguments.contains("known-secret"));
        assertThat(objectMapper.readTree(received.getFirst())).isEqualTo(objectMapper.readTree("""
                {"commentId":"comment-1","body":"Answer [REDACTED]","quotes":["[REDACTED]",7],"nested":{"line":"see [REDACTED]"},"retry":true}
                """));
    }

    private JsonNode localReviewCall(PermissionBroker broker, String token, String tool, String arguments)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(tool, arguments))).body().orElseThrow();
    }


    @Test
    void aToolsOnlyRegistrationHidesThePermissionTools()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Tool graph = new CodeQueryBridge(query -> "symbols for " + query.query().orElseThrow(), "use the graph").tool();
        McpRegistration registration = broker.registerTools("task-3", temporaryDirectory, TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableList.of(graph))
                .orElseThrow();
        assertThat(registration.endpoint().toString()).matches("http://127\\.0\\.0\\.1:\\d+/mcp");

        JsonNode listed = listTools(broker, registration.token());
        assertThat(listed.at("/result/tools")).hasSize(4);
        assertThat(listed.at("/result/tools/0/name").asText()).isEqualTo("code_query");
        JsonNode called = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"code_query","arguments":{"operation":"SYMBOLS","path":"src/main.go","limit":20,"query":"routes"}}}
                """)).body().orElseThrow();
        assertThat(called.at("/result/content/0/text").asText()).isEqualTo("symbols for routes");
        JsonNode hidden = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"permission_prompt","arguments":{"tool_name":"Bash","input":{}}}}
                """)).body().orElseThrow();
        assertThat(hidden.at("/error/code").asInt()).isEqualTo(-32602);
        JsonNode memory = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"read_ci_memory","arguments":{}}}
                """)).body().orElseThrow();
        assertThat(memory.at("/error/code").asInt()).isEqualTo(-32602);

        registration.close();
        assertThat(broker.handle(registration.token(), objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void codexRegistrationsServeTheirOwnTaskToolsWithoutClaudePermissions()
            throws Exception
    {
        AtomicInteger memoryReads = new AtomicInteger();
        PermissionBroker broker = new PermissionBroker(objectMapper);
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of());
        AgentApprovalHandler approvals = (request, timeout) -> {
            throw new AssertionError("Codex must not invoke Claude permission callbacks");
        };
        try (McpRegistration first = broker.registerTools("task-ci-first", temporaryDirectory,
                TaskScopes.of(AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL, false, false), approvals, redactor,
                ImmutableList.of(memory("task-ci-first", memoryReads))).orElseThrow();
                McpRegistration second = broker.registerTools("task-ci-second", temporaryDirectory,
                        TaskScopes.of(AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL, false, false), approvals, redactor,
                        ImmutableList.of(memory("task-ci-second", memoryReads))).orElseThrow()) {
            JsonNode listRequest = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                    """);
            assertThat(broker.handle(
                    first.token(),
                    listRequest).body().orElseThrow().at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                    .containsExactly("read_ci_memory", "codej_list_tools", "codej_get_tool", ToolServer.DEFAULT.callTool());
            JsonNode readRequest = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_ci_memory","arguments":{}}}
                    """);
            assertThat(broker.handle(first.token(), readRequest).body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("task-ci-first: recent");
            assertThat(broker.handle(second.token(), readRequest).body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("task-ci-second: recent");
            JsonNode permissionRequest = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                    "name":"permission_prompt","arguments":{"tool_name":"Bash","input":{"command":"git push"}}}}
                    """);
            assertThat(broker.handle(first.token(), permissionRequest).body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(broker.handle("wrong-token", readRequest).status()).isEqualTo(401);
            first.close();
            assertThat(broker.handle(first.token(), readRequest).status()).isEqualTo(401);
            assertThat(broker.handle(second.token(), readRequest).body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("task-ci-second: recent");
            assertThat(memoryReads.get()).isEqualTo(3);
        }
    }

    private static Tool memory(String taskId, AtomicInteger reads)
    {
        return Tool.withSchema("read_ci_memory", "Read CI Autofix history", "{\"type\":\"object\"}", arguments -> {
            reads.incrementAndGet();
            return taskId + ": recent";
        });
    }

    @Test
    void reviewRegistrationsDoNotExposeCiMemory()
            throws Exception
    {
        ReviewBridge review = new ReviewBridge(ImmutableList.of(ReviewTool.of(
                "review_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "review context")), "Review this pull request");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        try (McpRegistration registration = McpTestSupport.registerReviewCodex(
                broker, "task-review", temporaryDirectory, AgentPermissionPolicy.AUTO, SecretRedactor.fromEnvironment(ImmutableMap.of()), review.tools()).orElseThrow()) {
            JsonNode listed = listTools(broker, registration.token());
            assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactly(
                    "review_context",
                    "codej_list_tools",
                    "codej_get_tool",
                    ToolServer.DEFAULT.callTool());
            assertThat(registration.toolNames()).containsExactlyElementsOf(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList());
            JsonNode denied = broker.handle(registration.token(), objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_ci_memory","arguments":{}}}
                    """)).body().orElseThrow();
            assertThat(denied.at("/error/code").asInt()).isEqualTo(-32602);
        }
    }

    @Test
    void codexReviewChildTokenCanOnlyCallReadsWhileRootDynamicToolCanSave()
            throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                ReviewTool.of("review_get_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> {
                    reads.incrementAndGet();
                    return "context";
                }).forSubagents(),
                ReviewTool.of("review_finish", "Finish the review", ImmutableMap.of("type", "object"), arguments -> {
                    writes.incrementAndGet();
                    return "saved known-secret";
                })), "Review only");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        String token;
        try (McpRegistration registration = McpTestSupport.registerReviewCodex(
                broker, "task-review", temporaryDirectory, AgentPermissionPolicy.AUTO,
                SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret")), review.tools()).orElseThrow()) {
            token = registration.token();
            PermissionBroker.CodexRootTools guarded = broker.guardCodexRootTools(registration);
            assertThat(guarded.mcpNames()).containsExactly("review_get_context", "codej_list_tools", "codej_get_tool");
            assertThat(guarded.rootTools()).singleElement().satisfies(tool ->
                    assertThat(tool).containsEntry("type", "function")
                            .containsEntry("name", ToolServer.DEFAULT.callTool()));
            JsonNode listed = listTools(broker, registration.token());
            assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactly(
                    "review_get_context",
                    "codej_list_tools",
                    "codej_get_tool");
            JsonNode read = localReviewCall(broker, token, "review_get_context", "{}");
            assertThat(read.at("/result/content/0/text").asText()).isEqualTo("context");
            JsonNode blocked = broker.handle(registration.token(), objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"review_finish","arguments":{}}}
                    """)).body().orElseThrow();
            assertThat(blocked.at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(writes).hasValue(0);
            assertThat(broker.callCodexRootTool(token, "review_finish", objectMapper.readTree("[]"))
                    .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(broker.callCodexRootTool(token, "unknown", objectMapper.createObjectNode())
                    .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(broker.callCodexRootTool(token, "review_get_context", objectMapper.createObjectNode())
                    .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(writes).hasValue(0);
            JsonNode saved = rootToolCall(broker, token, "review_finish", "{}");
            assertThat(saved.at("/result/content/0/text").asText()).isEqualTo("saved [REDACTED]");
            assertThat(reads).hasValue(1);
            assertThat(writes).hasValue(1);
        }
        assertThat(broker.handle(token, objectMapper.readTree("{}")).status()).isEqualTo(401);
        assertThat(broker.callCodexRootTool(token, "review_finish", objectMapper.createObjectNode())
                .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(writes).hasValue(1);
    }

    @Test
    void codexReviewChildrenReadMemoryWhileOnlyRootDynamicCallsCanSaveIt()
            throws Exception
    {
        List<String> calls = new ArrayList<>();
        Tool search = Tool.withSchema(MemoryBridge.TOOL_NAME, "Search memory", "{\"type\":\"object\"}", arguments -> {
            calls.add("search " + arguments);
            return "memory result";
        }).forSubagents();
        Tool save = Tool.withSchema(MemoryBridge.SAVE_TOOL_NAME, "Save memory", "{\"type\":\"object\"}", arguments -> {
            calls.add("save " + arguments);
            return "saved";
        });
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                ReviewTool.of("review_get_context", "Read context", ImmutableMap.of("type", "object"), arguments -> "context").forSubagents()), "Review");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        List<Tool> sessionTools = ImmutableList.<Tool>builder().addAll(review.tools()).add(search, save).build();
        try (McpRegistration registration = broker.registerTools("task-review", temporaryDirectory, McpTestSupport.taskScope(AgentPermissionPolicy.AUTO, true),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()), sessionTools).orElseThrow()) {
            PermissionBroker.CodexRootTools guarded = broker.guardCodexRootTools(registration);
            // Children read memory through MCP; saving is a root-only tool, served by the root dispatcher alone.
            assertThat(guarded.mcpNames()).containsExactly("review_get_context", MemoryBridge.TOOL_NAME, "codej_list_tools", "codej_get_tool");
            assertThat(localReviewCall(broker, registration.token(), MemoryBridge.TOOL_NAME, "{\"action\":\"SEARCH\"}")
                    .at("/result/content/0/text").asText()).isEqualTo("memory result");
            assertThat(localReviewCall(broker, registration.token(), MemoryBridge.SAVE_TOOL_NAME, "{\"action\":\"REMEMBER\"}").at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(rootToolCall(broker, registration.token(), MemoryBridge.SAVE_TOOL_NAME, "{\"action\":\"REMEMBER\"}")
                    .at("/result/content/0/text").asText()).isEqualTo("saved");
            assertThat(calls).containsExactly("search {\"action\":\"SEARCH\"}", "save {\"action\":\"REMEMBER\"}");
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void rootOnlyTaskToolsAreRootBoundOnBothProvidersWithoutRestrictingOrdinaryDevelopmentTools()
            throws Exception
    {
        AtomicInteger starts = new AtomicInteger();
        Tool related = rootTool(arguments -> {
            starts.incrementAndGet();
            return "linked known-secret";
        });
        PermissionBroker broker = new PermissionBroker(objectMapper);
        var redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        try (var registration = registerCodexTask(broker, "task-related", redactor, related)) {
            assertThat(registration.rootTools()).isTrue();
            var guard = broker.guardCodexRootTools(registration);
            assertThat(guard.mcpNames()).doesNotContain(RelatedWorkspaceBridge.TOOL_NAME);
            assertThat(localReviewCall(broker, registration.token(), RelatedWorkspaceBridge.TOOL_NAME, "{}").at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(starts).hasValue(0);
            assertThat(rootToolCall(broker, registration.token(), RelatedWorkspaceBridge.TOOL_NAME, "{}").at("/result/content/0/text").asText()).isEqualTo("linked [REDACTED]");
            assertThat(starts).hasValue(1);
        }
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("related runtime"));
        try (var registration = broker.register("task-related", temporaryDirectory, runtime, runtime.resolve("provider"), TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                ImmutableMap.of(), ImmutableList.of(related))) {
            assertThat(registration.rootTools()).isTrue();
            String hook = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow())).at("/hooks/PreToolUse/0/hooks/0/command").asText();
            assertThat(gate(hook, "{\"tool_name\":\"mcp__codej__related_workspace_work\",\"tool_input\":{}}")).isZero();
            assertThat(gate(hook, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__codej__related_workspace_work\",\"tool_input\":{}}")).isEqualTo(2);
            assertThat(gate(hook, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__codej__codej_call_tool\",\"tool_input\":{\"name\":\"related_workspace_work\"}}"))
                    .isEqualTo(2);
            assertThat(gate(hook, "{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"general-purpose\"}}")).isZero();
            assertThat(gate(hook, "broken JSON")).isEqualTo(2);
            assertThat(localReviewCall(broker, registration.token(), RelatedWorkspaceBridge.TOOL_NAME, "{}").at("/result/content/0/text").asText())
                    .isEqualTo("linked [REDACTED]");
            assertThat(starts).hasValue(2);
        }
    }

    @Test
    void writesOwnerOnlyConfigAndBridgesTypedApprovalRequests()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        AtomicReference<Duration> approvalTimeout = new AtomicReference<>();
        McpRegistration registration = broker.register("task-1",
                temporaryDirectory,
                temporaryDirectory,
                temporaryDirectory,
                TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false),
                (request, timeout) -> {
                    approval.set(request);
                    approvalTimeout.set(timeout);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret")),
                ImmutableMap.of(),
                ImmutableList.of());

        JsonNode config = objectMapper.readTree(Files.readString(registration.configFile().orElseThrow()));
        String authorization = config.at("/mcpServers/codej/headers/Authorization").asText();
        String token = authorization.substring("Bearer ".length());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(registration.configFile().orElseThrow())))
                .isEqualTo("rw-------");

        McpHttpResponse initialized = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                "params":{"protocolVersion":"2025-03-26","capabilities":{},
                "clientInfo":{"name":"claude-code","version":"2.1.233"}}}
                """));
        assertThat(initialized.status()).isEqualTo(200);
        assertThat(initialized.body().orElseThrow().at("/result/protocolVersion").asText())
                .isEqualTo("2025-03-26");

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools/0/name").asText())
                .isEqualTo("permission_prompt");
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);

        McpHttpResponse called = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                "name":"permission_prompt","arguments":{"tool_name":"Bash",
                "input":{"command":"echo known-secret"}}}}
                """));
        JsonNode permission = objectMapper.readTree(called.body().orElseThrow().at("/result/content/0/text").asText());
        assertThat(permission.get("behavior").asText()).isEqualTo("allow");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND);
        assertThat(approval.get().detail()).contains("[REDACTED]").doesNotContain("known-secret");
        assertThat(approvalTimeout.get()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.at("/mcpServers/codej/timeout").asLong())
                .isGreaterThan(approvalTimeout.get().toMillis())
                .isLessThanOrEqualTo(approvalTimeout.get().plusSeconds(30).toMillis());

        assertThat(broker.handle("wrong", objectMapper.createObjectNode()).status()).isEqualTo(401);
        registration.close();
        assertThat(registration.configFile().orElseThrow()).doesNotExist();
        assertThat(broker.handle(token, objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void carriesQuestionAnswersBackOnTheToolInputItAsked()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Map<String, String> answers = ImmutableMap.of("Which issue should I work on?", "Both", "Never asked", "Ignored");
        try (McpRegistration registration = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty(), answers)),
                SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), ImmutableList.of())) {
            String token = objectMapper.readTree(Files.readString(registration.configFile().orElseThrow()))
                    .at("/mcpServers/codej/headers/Authorization").asText().substring("Bearer ".length());
            String questions = "[{\"question\":\"Which issue should I work on?\",\"header\":\"Issue\",\"options\":[{\"label\":\"Both\"}]}]";
            McpHttpResponse called = broker.handle(token, objectMapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"permission_prompt\",\"arguments\":{\"tool_name\":\"AskUserQuestion\","
                            + "\"input\":{\"questions\":" + questions + "}}}}"));

            JsonNode permission = objectMapper.readTree(called.body().orElseThrow().at("/result/content/0/text").asText());
            assertThat(permission.get("behavior").asText()).isEqualTo("allow");
            // Claude reads the answer off the input it asked with, keyed by the question itself.
            assertThat(permission.at("/updatedInput/answers/Which issue should I work on?").asText()).isEqualTo("Both");
            // An answer to a question that was never asked would make Claude drop the whole input.
            assertThat(permission.at("/updatedInput/answers").size()).isEqualTo(1);
            assertThat(permission.at("/updatedInput/questions")).isEqualTo(objectMapper.readTree(questions));

            // Every other tool runs on the input it asked with.
            McpHttpResponse command = broker.handle(token, objectMapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"permission_prompt\",\"arguments\":{\"tool_name\":\"Bash\","
                            + "\"input\":{\"command\":\"npm test\"}}}}"));
            assertThat(objectMapper.readTree(command.body().orElseThrow().at("/result/content/0/text").asText()).at("/updatedInput"))
                    .isEqualTo(objectMapper.readTree("{\"command\":\"npm test\"}"));
        }
    }

    @Test
    void enforcesCiPolicyAndInjectsTaskIdentityIntoMemoryReads()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("worktree"));
        Path provider = temporaryDirectory.resolve("provider");
        AtomicReference<ProviderApprovalRequest> requestedApproval = new AtomicReference<>();
        AtomicInteger approvalCount = new AtomicInteger();
        McpRegistration registration = broker.register("task-ci",
                worktree,
                temporaryDirectory,
                provider,
                TaskScopes.of(AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL, false, false),
                (request, timeout) -> {
                    requestedApproval.set(request);
                    approvalCount.incrementAndGet();
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                ImmutableMap.of(),
                ImmutableList.of(Tool.withSchema("read_ci_memory", "Read CI Autofix history", "{\"type\":\"object\"}", arguments -> "bounded memory")));
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/failIfUnavailable").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isTrue();
        assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
        assertThat(settings.at("/sandbox/network/allowedDomains"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.of("*")));
        assertThat(settings.at("/sandbox/network/allowLocalBinding").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/filesystem/denyRead").toString()).contains(".config/gh");
        assertThat(settings.at("/permissions/deny").toString())
                .contains("WebFetch", "Bash(git credential:*)", "Bash(git push:*)", "Read(~/.config/gh/**)");

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(5);
        assertThat(listed.body().orElseThrow().at("/result/tools/1/name").asText())
                .isEqualTo("read_ci_memory");

        McpHttpResponse read = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_ci_memory","arguments":{"query":"compile failure"}}}
                """));
        assertThat(read.body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("bounded memory");

        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("src/Main.java").toString()))
                .isEqualTo("allow");
        assertThat(approvalCount).hasValue(0);
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside").toString()))
                .isEqualTo("deny");
        assertThat(requestedApproval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.FILE_CHANGE);
        Path escaped = Files.createDirectories(temporaryDirectory.resolve("escaped"));
        Files.createSymbolicLink(worktree.resolve("linked"), escaped);
        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("linked/Main.java").toString()))
                .isEqualTo("deny");
        assertThat(approvalCount).hasValue(2);
        assertThat(permission(broker, token, "Bash", "command", "git push origin HEAD"))
                .isEqualTo("deny");
        assertThat(requestedApproval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND);
        assertThat(approvalCount).hasValue(3);

        Path settingsFile = registration.settingsFile().orElseThrow();
        registration.close();
        assertThat(registration.configFile().orElseThrow()).doesNotExist();
        assertThat(settingsFile).doesNotExist();
        assertThat(broker.handle(token, objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void forwardsCiPermissionRequestsToTheApprovalHandler()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("ci-approval-worktree"));
        AtomicReference<ProviderApprovalRequest> requestedApproval = new AtomicReference<>();
        try (McpRegistration registration = broker.register("task-ci-approval",
                worktree,
                temporaryDirectory,
                temporaryDirectory.resolve("ci-approval-provider"),
                TaskScopes.of(AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL, false, false),
                (request, timeout) -> {
                    requestedApproval.set(request);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                ImmutableMap.of(),
                ImmutableList.of())) {
            Map<String, Object> retry = ImmutableMap.of("command", "mvn test", "dangerouslyDisableSandbox", true);
            assertThat(permission(broker, token(registration), "Bash", retry))
                    .isEqualTo("allow");
            assertThat(requestedApproval.get().title()).isEqualTo("Allow Claude unsandboxed command");
            assertThat(requestedApproval.get().detail()).contains("mvn test", "\"dangerouslyDisableSandbox\":true", "host file access");
            assertThat(requestedApproval.get().availableDecisions()).containsExactly(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY);
        }
    }

    @Test
    void sandboxesCherryPickWithoutExposingCiMemory()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("cherry-pick-worktree"));
        List<String> allowed = ImmutableList.of("mvn -q test", "git add src/Main.java", "git commit --fixup=" + "a".repeat(40), "git commit --amend --no-edit");
        List<ProviderApprovalRequest> requests = new CopyOnWriteArrayList<>();
        McpRegistration registration = broker.register("task-cherry-pick", worktree, temporaryDirectory, temporaryDirectory.resolve("cherry-pick-provider"),
                TaskScopes.of(AgentPermissionPolicy.CHERRY_PICK, SessionAccess.FULL, false, false),
                (request, timeout) -> {
                    requests.add(request);
                    return CompletableFuture.completedFuture(request.sandboxed() && request.command().filter(allowed::contains).isPresent()
                            ? ProviderApprovalDecision.allow(Optional.empty()) : ProviderApprovalDecision.deny());
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), ImmutableList.of());
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isFalse();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
        assertThat(settings.at("/sandbox/network/allowedDomains")).isEmpty();
        assertThat(settings.at("/permissions/deny").toString())
                .contains("WebFetch", "Bash(git push:*)", "Bash(gh:*)", "Read(~/.ssh/**)");

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);
        assertThat(listed.body().orElseThrow().at("/result/tools/0/name").asText()).isEqualTo("permission_prompt");

        McpHttpResponse memory = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{}}}
                """));
        assertThat(memory.body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);

        // Every command goes to the handler; an edit in the session's own folders does not.
        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("core/Main.java").toString()))
                .isEqualTo("allow");
        assertThat(requests).isEmpty();
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside.txt").toString()))
                .isEqualTo("deny");
        assertThat(permission(broker, token, "Bash", "command", "git push origin HEAD"))
                .isEqualTo("deny");
        assertThat(permission(broker, token, "Bash", "command", "mvn -q test"))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git add src/Main.java"))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git commit --fixup=" + "a".repeat(40)))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git commit --amend --no-edit"))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git commit -m unexpected"))
                .isEqualTo("deny");
        assertThat(permission(broker, token, "Bash", "command", "git cherry-pick --continue"))
                .isEqualTo("deny");
        Map<String, Object> unsandboxed = ImmutableMap.of("command", "mvn test", "dangerouslyDisableSandbox", true);
        assertThat(permission(broker, token, "Bash", unsandboxed)).isEqualTo("deny");
        assertThat(requests).filteredOn(request -> request.kind() == ProviderApprovalRequest.Kind.COMMAND)
                .extracting(request -> request.command().orElseThrow())
                .containsExactly("git push origin HEAD", "mvn -q test", "git add src/Main.java", "git commit --fixup=" + "a".repeat(40),
                        "git commit --amend --no-edit", "git commit -m unexpected", "git cherry-pick --continue", "mvn test");
        assertThat(requests.getLast().sandboxed()).isFalse();
    }

    @Test
    void reviewsInteractiveGitHubCommandsWhileKeepingTheSandbox()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("auto-worktree"));
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        McpRegistration registration = broker.register("task-auto",
                worktree,
                temporaryDirectory,
                temporaryDirectory.resolve("auto-provider"),
                TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false),
                (request, timeout) -> {
                    approval.set(request);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                ImmutableMap.of(),
                ImmutableList.of());
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isFalse();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isTrue();
        assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
        assertThat(settings.at("/sandbox/network/allowedDomains"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.of("*")));
        assertThat(settings.at("/sandbox/network/allowLocalBinding").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/network/allowUnixSockets"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.of("/var/run/docker.sock", "~/.docker/run/docker.sock", "~/.orbstack/run/docker.sock")));
        assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.of()));
        assertThat(settings.at("/permissions/allow").isMissingNode()).isTrue();
        assertThat(settings.at("/permissions/deny")).isEqualTo(objectMapper.valueToTree(ImmutableList.of(
                "Read(~/.config/gh/**)", "Read(~/.git-credentials)", "Read(~/.netrc)", "Read(~/.ssh/**)")));
        assertThat(settings.at("/sandbox/filesystem/denyRead")).isEqualTo(objectMapper.valueToTree(ImmutableList.of()));
        assertThat(settings.at("/autoMode/classifyAllShell").asBoolean()).isTrue();
        assertThat(settings.at("/autoMode/hard_deny"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.<String>builder().add("$defaults").addAll(AgentCommandPolicy.restrictions()).build()));
        assertThat(settings.at("/sandbox/filesystem/denyWrite").toString())
                .contains(temporaryDirectory.toRealPath().toString());

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);

        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("src/Main.java").toString()))
                .isEqualTo("allow");
        assertThat(approval.get()).isNull();
        assertThat(permission(broker, token, "FutureTool", "file_path", worktree.resolve("src/Main.java").toString()))
                .isEqualTo("deny");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.TOOL);
        assertThat(approval.get().sandboxed()).isFalse();
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside.txt").toString()))
                .isEqualTo("deny");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.FILE_CHANGE);
        assertThat(approval.get().sandboxed()).isFalse();
        assertThat(permission(broker, token, "WebFetch", "url", "https://api.github.com/repos/acme/warehouse")).isEqualTo("deny");
        assertThat(approval.get().scope()).hasValueSatisfying(scope -> {
            assertThat(scope.operation()).isEqualTo(ProviderApprovalScope.Operation.NETWORK_ACCESS);
            assertThat(scope.hosts()).containsExactly("api.github.com");
        });
        assertThat(permission(broker, token, "Bash", "command", "gh pr view 42 --repo github.com/acme/warehouse")).isEqualTo("deny");
        assertThat(approval.get().scope()).hasValueSatisfying(scope -> {
            assertThat(scope.operation()).isEqualTo(ProviderApprovalScope.Operation.GITHUB_READ);
            assertThat(scope.repository()).contains("acme/warehouse");
        });
        String chain = "git status && gh pr view 42 && git push origin HEAD";
        assertThat(permission(broker, token, "Bash", "command", chain)).isEqualTo("deny");
        assertThat(approval.get().detail()).contains(chain);
        assertThat(approval.get().scope()).isEmpty();
        assertThat(approval.get().sandboxed()).isTrue();
        Map<String, Object> retry = ImmutableMap.of("command", "mvn test", "dangerouslyDisableSandbox", true);
        assertThat(permission(broker, token, "Bash", retry)).isEqualTo("deny");
        assertThat(approval.get().title()).isEqualTo("Allow Claude unsandboxed command");
        assertThat(approval.get().sandboxed()).isFalse();
        // Bare Maven runs outside the sandbox without asking for it, so its card says so too.
        for (String maven : ImmutableList.of("mvn -q verify", "cd core && ./mvnw test", "mvnd install")) {
            assertThat(permission(broker, token, "Bash", "command", maven)).isEqualTo("deny");
            assertThat(approval.get().title()).isEqualTo("Allow Claude unsandboxed command");
            assertThat(approval.get().detail()).contains("host file access");
            assertThat(approval.get().sandboxed()).isFalse();
        }
        assertThat(permission(broker, token, "Bash", "command", "mvnx --help")).isEqualTo("deny");
        assertThat(approval.get().sandboxed()).isTrue();
        registration.close();
    }

    @Test
    void sharedReviewerRetainsReportToolsButCannotEditTheCheckout()
            throws Exception
    {
        Path worktree = Files.createDirectory(temporaryDirectory.resolve("shared-worktree"));
        Path runtime = Files.createDirectory(temporaryDirectory.resolve("shared-runtime"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(ReviewTool.of(
                "review_save_finding", "Save a finding", ImmutableMap.of("type", "object"), input -> "saved")), "Review only");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        try (McpRegistration registration = broker.register("shared-review", worktree, runtime, runtime.resolve("provider"), new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.REVIEW_READ_ONLY, true, false), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), review.tools())) {
            assertThat(registration.toolNames()).contains("review_save_finding");
            JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
            assertThat(settings.at("/sandbox/filesystem/denyWrite")).isEqualTo(objectMapper.valueToTree(ImmutableList.of(
                    runtime.toRealPath().toString(),
                    worktree.toRealPath().toString())));
            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
            assertThat(permission(broker, token(registration), "Edit", "file_path", worktree.resolve("source.txt").toString())).isEqualTo("deny");
        }
    }

    @Test
    void grantsClaudeOnlyTheListedBuildCaches()
            throws Exception
    {
        Path home = Files.createDirectories(temporaryDirectory.resolve("build-home")).toRealPath();
        Path external = Files.createDirectories(temporaryDirectory.resolve("external"));
        var environment = ImmutableMap.of("HOME", home.toString(), "TMPDIR", Files.createDirectory(temporaryDirectory.resolve("host-tmp")).toString());
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("build-worktree"));
        Path runtime = Files.createDirectory(temporaryDirectory.resolve("build-runtime"));
        Files.createSymbolicLink(worktree.resolve("linked"), external);
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.CI_AUTOFIX)) {
            AtomicInteger approvalCount = new AtomicInteger();
            try (McpRegistration registration = broker.register("build-" + policy,
                    worktree,
                    runtime,
                    runtime.resolve("build-provider-" + policy),
                    TaskScopes.of(policy, SessionAccess.FULL, false, false).withWritableRoots(TaskScopes.buildCaches(home)),
                    (request, timeout) -> {
                        approvalCount.incrementAndGet();
                        return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                    },
                    SecretRedactor.fromEnvironment(environment),
                    environment,
                    ImmutableList.of())) {
                JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
                List<String> expected = ImmutableList.<String>builder().add(home.resolve(".m2").toString(), home.resolve(".gradle").toString(),
                        home.resolve(".npm").toString()).build();
                assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                        .isEqualTo(objectMapper.valueToTree(expected));
                assertThat(expected).doesNotContain(external.toString());
                String token = token(registration);
                assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("src/Main.java").toString()))
                        .isEqualTo("allow");
                assertThat(permission(broker, token, "Write", "file_path", home.resolve(".m2/repository/test.pom").toString()))
                        .isEqualTo("allow");
                assertThat(permission(broker, token, "NotebookEdit", "notebook_path", home.resolve(".gradle/build.ipynb").toString()))
                        .isEqualTo("allow");
                assertThat(approvalCount).hasValue(0);
                assertThat(permission(broker, token, "Write", "file_path", external.resolve("test.pom").toString()))
                        .isEqualTo("deny");
                assertThat(approvalCount).hasValue(1);
                assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("linked/test.pom").toString()))
                        .isEqualTo("deny");
                assertThat(approvalCount).hasValue(2);
                assertThat(permission(broker, token, "Bash", "command", "mvn test"))
                        .isEqualTo("deny");
                assertThat(approvalCount).hasValue(3);
            }
        }
        // A cherry-pick downloads nothing, so the host application lists no caches and none is writable.
        try (McpRegistration registration = broker.register("cherry-pick-build", worktree, runtime, runtime.resolve("cherry-pick-provider"),
                TaskScopes.of(AgentPermissionPolicy.CHERRY_PICK, SessionAccess.FULL, false, false), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(environment), environment, ImmutableList.of())) {
            JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
            assertThat(settings.at("/sandbox/filesystem/allowWrite").toString()).doesNotContain(home.resolve(".m2").toString(), home.resolve(".gradle").toString());
        }
    }

    @Test
    void grantsLinkedGitMetadataOnlyToWritableTaskTurns()
            throws Exception
    {
        Path root = temporaryDirectory.toRealPath();
        Path worktree = Files.createDirectory(root.resolve("linked-worktree"));
        Path common = Files.createDirectories(root.resolve("repository/.git"));
        Path privateGit = Files.createDirectories(common.resolve("worktrees/task"));
        Path objects = Files.createDirectory(common.resolve("objects"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + privateGit + "\n");
        Files.writeString(privateGit.resolve("gitdir"), worktree.resolve(".git") + "\n");
        Files.writeString(privateGit.resolve("commondir"), "../..\n");
        Files.writeString(privateGit.resolve("HEAD"), "ref: refs/heads/codej/task\n");
        Path branch = common.resolve("refs/heads/codej/task");
        Path reflog = common.resolve("logs/refs/heads/codej/task");
        List<String> expected = ImmutableList.<String>builder().add(
                privateGit.toString(), objects.toString(), branch.toString(), branch + ".lock",
                reflog.toString(), reflog + ".lock").build();
        Path runtime = Files.createDirectory(root.resolve("linked-runtime"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(ReviewTool.of(
                "review_context", "Read review context", ImmutableMap.of("type", "object"), input -> "context")), "Review only");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        for (AgentPermissionPolicy policy : AgentPermissionPolicy.values()) {
            String taskId = policy.name().toLowerCase();
            try (McpRegistration registration = broker.register(taskId, worktree, runtime, root.resolve("provider-" + taskId),
                    new AgentPermissionScope(policy, SessionAccess.FULL, false, false).withCommits(true),
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), ImmutableList.of())) {
                JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
                assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                        .isEqualTo(objectMapper.valueToTree(expected));
                // The Git directory is writable, but not the files that tell the host application's own Git where the repository and its settings are.
                assertThat(settings.at("/sandbox/filesystem/denyWrite")).isEqualTo(objectMapper.valueToTree(ImmutableList.of(runtime.toString(),
                        privateGit.resolve("commondir").toString(), privateGit.resolve("gitdir").toString(), privateGit.resolve("config.worktree").toString())));
            }
        }
        for (String taskId : ImmutableList.of("review", "read-only")) {
            SessionAccess access = taskId.equals("read-only") ? SessionAccess.READ_ONLY : SessionAccess.FULL;
            try (McpRegistration registration = broker.register(taskId, worktree, runtime, root.resolve("provider-" + taskId), new AgentPermissionScope(AgentPermissionPolicy.AUTO, access, taskId.equals("review"), false), (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), taskId.equals("review") ? review.tools() : ImmutableList.of())) {
                JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
                // A session that may change nothing writes no cache or temporary file a later build would run.
                assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                        .isEqualTo(objectMapper.valueToTree(ImmutableList.of()));
                assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isEqualTo(access == SessionAccess.FULL);
                assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
            }
        }
    }

    @Test
    void rejectsMalformedLinkedGitMetadataWithoutLeavingClaudeConfiguration()
            throws Exception
    {
        Path root = temporaryDirectory.toRealPath();
        Path worktree = Files.createDirectory(root.resolve("malformed-worktree"));
        Files.createSymbolicLink(worktree.resolve(".git"), root.resolve("missing-gitdir"));
        Path runtime = Files.createDirectory(root.resolve("malformed-runtime"));
        Path provider = root.resolve("malformed-provider");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        assertThatThrownBy(() -> broker.register("malformed", worktree, runtime, provider, TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), ImmutableList.of()))
                .hasMessageContaining("regular file");
        assertThat(provider.resolve("codej-mcp.json")).doesNotExist();
        assertThat(provider.resolve("sandbox-settings.json")).doesNotExist();
    }

    @Test
    void scopesAuthenticationAndNativeReviewIndependentlyOfProviderLogin()
            throws Exception
    {
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("auth-worktree"));
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("auth-runtime"));
        String socket = temporaryDirectory.resolve("ssh-agent.sock").toString();
        String ghConfig = temporaryDirectory.resolve("github-config").toString();
        String xdgConfig = temporaryDirectory.resolve("xdg-config").toString();
        PermissionBroker broker = new PermissionBroker(objectMapper);
        for (AgentPermissionPolicy policy : AgentPermissionPolicy.values()) {
            for (SessionAccess access : SessionAccess.values()) {
                for (boolean requestedReview : ImmutableList.of(false, true)) {
                    boolean reviewSession = requestedReview && access != SessionAccess.READ_ONLY && access != SessionAccess.TOOLS_ONLY;
                    boolean interactive = policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL && !reviewSession;
                    Optional<JsonNode> previous = Optional.empty();
                    for (String loginVariable : ImmutableList.of("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN")) {
                        var environment = ImmutableMap.of(loginVariable, "provider-secret", "GH_TOKEN", "github-secret",
                                "SSH_AUTH_SOCK", socket, "GH_CONFIG_DIR", ghConfig, "XDG_CONFIG_HOME", xdgConfig);
                        try (var registration = broker.register("task-auth", worktree, runtime, runtime.resolve("provider"), taskScope(policy, access, reviewSession),
                                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                                SecretRedactor.fromEnvironment(environment), environment, ImmutableList.of())) {
                            String json = Files.readString(registration.settingsFile().orElseThrow());
                            JsonNode settings = objectMapper.readTree(json);
                            assertThat(settings.path("autoMemoryEnabled")).isEqualTo(objectMapper.valueToTree(false));
                            assertThat(json).doesNotContain("provider-secret", "github-secret");
                            boolean canRetryOutsideSandbox = access == SessionAccess.FULL
                                    && policy != AgentPermissionPolicy.CHERRY_PICK;
                            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean())
                                    .isEqualTo(canRetryOutsideSandbox);
                            assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
                            boolean ciBuildNetwork = policy == AgentPermissionPolicy.CI_AUTOFIX && access == SessionAccess.FULL;
                            assertThat(settings.at("/sandbox/network/allowLocalBinding"))
                                    .isEqualTo((policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL) || ciBuildNetwork
                                            ? objectMapper.valueToTree(true)
                                            : objectMapper.missingNode());
                            // Without macOS's trust agent, gh fails every TLS check inside the sandbox.
                            assertThat(settings.at("/sandbox/enableWeakerNetworkIsolation").asBoolean())
                                    .isEqualTo((policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL) || ciBuildNetwork);
                            boolean dockerAccess = access == SessionAccess.FULL && policy != AgentPermissionPolicy.CHERRY_PICK;
                            assertThat(settings.at("/sandbox/network/allowUnixSockets"))
                                    .isEqualTo(dockerAccess
                                            ? objectMapper.valueToTree(ImmutableList.of("/var/run/docker.sock", "~/.docker/run/docker.sock", "~/.orbstack/run/docker.sock"))
                                            : objectMapper.missingNode());
                            assertThat(settings.at("/sandbox/network/allowAllUnixSockets").isMissingNode()).isTrue();
                            assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
                            // Maven's resolver cannot log in to Claude's sandbox proxy; code under review stays sandboxed.
                            boolean unsandboxedMaven = access == SessionAccess.FULL && !reviewSession
                                    && (policy == AgentPermissionPolicy.AUTO || policy == AgentPermissionPolicy.CI_AUTOFIX);
                            assertThat(settings.at("/sandbox/excludedCommands"))
                                    .isEqualTo(objectMapper.valueToTree(unsandboxedMaven ? ImmutableList.of("mvn *", "./mvnw *", "mvnd *") : ImmutableList.of()));
                            assertThat(PermissionBroker.sandboxInstruction(taskScope(policy, access, reviewSession)).contains("`mvn ...`, `./mvnw ...`, `mvnd ...`"))
                                    .isEqualTo(unsandboxedMaven);
                            if (interactive) {
                                assertThat(settings.at("/sandbox/network/allowedDomains"))
                                        .isEqualTo(objectMapper.valueToTree(ImmutableList.of("*")));
                                assertThat(settings.at("/permissions/deny")).isEqualTo(objectMapper.valueToTree(ImmutableList.of(
                                        "Read(~/.config/gh/**)", "Read(~/.git-credentials)", "Read(~/.netrc)", "Read(~/.ssh/**)")));
                                assertThat(settings.at("/sandbox/filesystem/denyRead")).isEqualTo(objectMapper.valueToTree(ImmutableList.of()));
                                assertThat(settings.at("/autoMode/classifyAllShell").asBoolean()).isTrue();
                                assertThat(settings.at("/autoMode/environment").isMissingNode()).isTrue();
                                assertThat(settings.path("hooks").isMissingNode()).isTrue();
                            }
                            else {
                                // An attended review reads remotes with the stored Git and gh login and never writes to them.
                                boolean reviewReads = reviewSession && policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL;
                                assertThat(settings.at("/sandbox/network/allowedDomains"))
                                        .isEqualTo(objectMapper.valueToTree(reviewReads || ciBuildNetwork ? ImmutableList.of("*") : ImmutableList.of()));
                                if (reviewSession) {
                                    assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isFalse();
                                    assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                                            .isEqualTo(objectMapper.valueToTree(ImmutableList.of()));
                                    assertThat(settings.at("/permissions/deny").toString()).contains("Bash(git commit:*)", "Bash(git update-ref:*)")
                                            .doesNotContain("Bash(git add", "Bash(git write-tree", "Bash(git update-index", "Bash(git symbolic-ref", "Bash(git reset");
                                    assertThat(settings.at("/autoMode/classifyAllShell").asBoolean()).isTrue();
                                    assertThat(settings.at("/autoMode/environment"))
                                            .isEqualTo(objectMapper.valueToTree(ImmutableList.of("$defaults", AgentCommandPolicy.reviewInstructions())));
                                    assertThat(settings.at("/autoMode/hard_deny"))
                                            .isEqualTo(objectMapper.valueToTree(ImmutableList.<String>builder().add("$defaults").addAll(AgentCommandPolicy.reviewRestrictions()).build()));
                                    boolean children = policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL;
                                    assertThat(settings.at("/permissions/deny").toString()).contains("\"Skill\"", "\"Workflow\"", "\"SendMessage\"");
                                    assertThat(settings.at("/permissions/deny").toString().contains("\"Agent\"")).isNotEqualTo(children);
                                    assertThat(settings.at("/permissions/deny").toString().contains("\"Task\"")).isNotEqualTo(children);
                                    assertThat(settings.path("hooks").isMissingNode()).isNotEqualTo(children);
                                    assertThat(Files.exists(registration.settingsFile().orElseThrow().resolveSibling("review-child-gate.sh"))).isEqualTo(children);
                                }
                                else {
                                    assertThat(settings.path("autoMode").isMissingNode()).isTrue();
                                    assertThat(settings.at("/permissions/deny").toString())
                                            .doesNotContain("Bash(git commit", "Bash(git update-ref", "Bash(git cherry-pick", "Bash(git reset");
                                }
                                String denied = settings.at("/permissions/deny").toString();
                                assertThat(denied).contains("Bash(git push:*)", "Read(~/.config/gh/**)", "Read(~/.ssh/**)");
                                if (reviewReads) {
                                    assertThat(settings.at("/sandbox/filesystem/denyRead")).isEqualTo(objectMapper.valueToTree(ImmutableList.of()));
                                    assertThat(denied).doesNotContain("Bash(gh:*)")
                                            .contains(
                                                    "Bash(git credential:*)",
                                                    "Bash(gh auth:*)",
                                                    "Bash(gh pr review:*)",
                                                    "Bash(gh pr comment)",
                                                    "Bash(gh pr merge:*)",
                                                    "Bash(gh issue comment:*)");
                                }
                                else {
                                    assertThat(denied).contains("Bash(gh:*)");
                                    assertThat(settings.at("/sandbox/filesystem/denyRead").toString()).contains(ghConfig, xdgConfig + "/gh");
                                }
                            }
                            previous.ifPresent(prior -> assertThat(settings).isEqualTo(prior));
                            previous = Optional.of(settings);
                        }
                    }
                }
            }
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void reviewChildGateKeepsWriteToolsWithTheRootAgent()
            throws Exception
    {
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("gate-worktree"));
        // The runtime directory sits under "Application Support", so the hook command must survive a space.
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("gate runtime"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                ReviewTool.of("review_get_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "context").forSubagents(),
                ReviewTool.of("review_finish", "Finish the review", ImmutableMap.of("type", "object"), arguments -> "{}")), "Review only");
        PermissionBroker broker = new PermissionBroker(objectMapper);
        Path gate;
        // The code query only reads, so a child may call it.
        List<Tool> tools = ImmutableList.<Tool>builder().addAll(review.tools()).add(new CodeQueryBridge(query -> "graph", "Use the graph").tool()).build();
        try (var registration = broker.register("task-gate", worktree, runtime, runtime.resolve("provider"), TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, true, false),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()), ImmutableMap.of(), tools)) {
            gate = registration.settingsFile().orElseThrow().resolveSibling("review-child-gate.sh");
            JsonNode hook = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow())).at("/hooks/PreToolUse/0");
            assertThat(hook.path("matcher").asText()).isEqualTo("^(Agent|Task|mcp__codej__.*)$");
            String command = hook.at("/hooks/0/command").asText();
            assertThat(command).isEqualTo("'" + gate + "'");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(gate))).isEqualTo("rwx------");
            String child = "\"agent_id\":\"a1\",\"agent_type\":\"Explore\",";

            // The root agent keeps every tool.
            assertThat(gate(command, "{\"tool_name\":\"mcp__codej__review_finish\",\"tool_input\":{}}")).isZero();
            // A child reads and explores, and nothing else.
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__codej__review_get_context\",\"tool_input\":{\"kind\":\"pr\"}}")).isZero();
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__codej__code_query\",\"tool_input\":{}}")).isZero();
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__codej__review_finish\",\"tool_input\":{}}")).isEqualTo(2);
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__codej__review_future_tool\",\"tool_input\":{}}")).isEqualTo(2);
            // Only read-only Explore children start.
            assertThat(gate(command, "{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"Explore\",\"prompt\":\"find callers\"}}")).isZero();
            assertThat(gate(command, "{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"general-purpose\",\"prompt\":\"fix it\"}}")).isEqualTo(2);
            assertThat(gate(command, "{\"tool_name\":\"Agent\",\"tool_input\":{\"prompt\":\"no type\"}}")).isEqualTo(2);
            // Text or arguments that merely mention agent_id do not make the root look like a child.
            assertThat(gate(command, "{\"tool_name\":\"mcp__codej__review_finish\",\"tool_input\":{\"body\":\"the \\\"agent_id\\\": field\",\"agent_id\":\"x\"}}")).isZero();
            // A call the gate cannot read is refused.
            assertThat(gate(command, "not json")).isEqualTo(2);
        }
        assertThat(gate).doesNotExist();
    }

    private McpRegistration registerCodexTask(PermissionBroker broker, String taskId, SecretRedactor redactor, Tool tool)
    {
        return broker.registerTools(taskId, temporaryDirectory, TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                ImmutableList.of(tool)).orElseThrow();
    }

    /** A task session's scope with the settings the host application gives it. */
    private static AgentPermissionScope taskScope(AgentPermissionPolicy policy, SessionAccess access, boolean review)
    {
        return TaskScopes.of(policy, access, review, false);
    }

    /** A task tool kept for the root agent, as related work is. */
    private static Tool rootTool(Tool.Handler<String> handler)
    {
        return Tool.withSchema(RelatedWorkspaceBridge.TOOL_NAME, "Start related work; root agent only", "{\"type\":\"object\"}", handler);
    }

    private McpRegistration registerCodexTask(PermissionBroker broker, AgentApprovalHandler approvals, SecretRedactor redactor)
    {
        return broker.registerTools("task-1", temporaryDirectory, TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false), approvals, redactor, ImmutableList.of()).orElseThrow();
    }

    private McpRegistration registerClaudeTask(PermissionBroker broker, AgentApprovalHandler approvals, SecretRedactor redactor)
    {
        return broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false), approvals, redactor, ImmutableMap.of(), ImmutableList.of());
    }

    private JsonNode listTools(PermissionBroker broker, String token)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
    }

    private static int gate(String hookCommand, String payload)
            throws Exception
    {
        Process process = new ProcessBuilder("/bin/sh", "-c", hookCommand).redirectErrorStream(true).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(payload.getBytes(UTF_8));
        }
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(10, SECONDS)).isTrue();
        return process.exitValue();
    }

    private String permission(PermissionBroker broker, String token, String toolName, String field, String value)
            throws Exception
    {
        return permission(broker, token, toolName, ImmutableMap.of(field, value));
    }

    private String permission(PermissionBroker broker, String token, String toolName, Map<String, ?> input)
            throws Exception
    {
        JsonNode request = objectMapper.valueToTree(ImmutableMap.of(
                "jsonrpc", "2.0", "id", 10, "method", "tools/call",
                "params", ImmutableMap.of("name", "permission_prompt", "arguments", ImmutableMap.of("tool_name", toolName, "input", input))));
        String result = broker.handle(token, request).body().orElseThrow().at("/result/content/0/text").asText();
        return objectMapper.readTree(result).get("behavior").asText();
    }

    private JsonNode rootToolCall(PermissionBroker broker, String token, String name, String arguments)
            throws Exception
    {
        return broker.callCodexRootTool(token, ToolServer.DEFAULT.callTool(),
                objectMapper.valueToTree(ImmutableMap.of("name", name, "arguments", objectMapper.readTree(arguments)))).body().orElseThrow();
    }

    private String token(McpRegistration registration)
            throws Exception
    {
        JsonNode config = objectMapper.readTree(Files.readString(registration.configFile().orElseThrow()));
        return config.at("/mcpServers/codej/headers/Authorization").asText().substring("Bearer ".length());
    }
}

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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalScope;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.driver.ToolSchemas;
import io.github.shiftinglabs.codej.cli.process.AgentPermissionManager;
import io.github.shiftinglabs.codej.cli.process.ApprovalScopes;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource.root;
import static io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest.Decision.DENY;
import static io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest.QUESTION_TOOL;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/** Serves the host application's task-scoped MCP tools. */
public final class PermissionBroker
        implements AutoCloseable
{
    private static final ObjectMapper SCHEMAS = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> SCHEMA_TYPE = new TypeReference<>() {};
    private static final String TOOL_DISCOVERY_INSTRUCTION = """
            Discover @SERVER@ capabilities with @LIST@ at the start of a turn. Choose the tools relevant
            to the request, then use @GET@ with name to read each tool's current description and inputSchema,
            including required parameters and allowed values. Use @CALL@ with name and arguments to run it.
            @SERVER@ tool names in task instructions refer to this catalog; they need not be directly exposed functions.
            Check the catalog and details before reporting a @SERVER@ tool as unavailable.
            Recheck details after resuming; saved tool descriptions can be outdated. Discovery does not grant permission
            or require using every tool. Existing task scope, user authorization and tool-specific rules still apply.
            In Codex, use the unqualified @CALL@ when available; it belongs to the main agent. Exploration
            children use their directly available read tools and report missing capabilities to the main agent.
            """;
    /** Root-only gate for review sessions; see {@code review-child-gate.sh} for what it refuses and why. */
    private static final String CHILD_GATE_SCRIPT = readChildGateScript();
    private static final Pattern SHELL_SAFE_TOOL_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final int MAX_DETAIL_CHARACTERS = 16 * 1024;
    /** Claude's own cap on one answer; a longer one would make it refuse the whole answered input. */
    private static final int MAX_ANSWER_CHARACTERS = 8 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final ToolServer toolServer;
    private final ClaudeSandboxSettings sandbox;
    private final Map<String, PermissionContext> contexts = new ConcurrentHashMap<>();
    private final Set<String> nativeOnlyTokens = ConcurrentHashMap.newKeySet();
    /** Runs while any token is registered, so no port is open between sessions. */
    private Optional<McpLoopbackServer> server = Optional.empty();

    public PermissionBroker(ObjectMapper objectMapper)
    {
        this(objectMapper, ToolServer.DEFAULT);
    }

    public PermissionBroker(ObjectMapper objectMapper, ToolServer toolServer)
    {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null")
                .copy()
                .registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.toolServer = requireNonNull(toolServer, "toolServer is null");
        this.sandbox = new ClaudeSandboxSettings(this.objectMapper, this.toolServer);
    }

    public ToolServer server()
    {
        return toolServer;
    }

    public McpRegistration register(
            String taskId, Path worktree, Path runtimeDirectory, Path providerDirectory, AgentPermissionScope scope,
            AgentApprovalHandler approvals, SecretRedactor redactor, Map<String, String> environment, List<Tool> sessionTools)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(runtimeDirectory, "runtimeDirectory is null");
        requireNonNull(providerDirectory, "providerDirectory is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(redactor, "redactor is null");
        requireNonNull(environment, "environment is null");
        String token = token();
        Path configFile = providerDirectory.resolve("codej-mcp.json");
        Path settingsFile = providerDirectory.resolve("sandbox-settings.json");
        Path childGateFile = providerDirectory.resolve("review-child-gate.sh");
        PermissionContext context = resolveContext(taskId, worktree, scope, approvals, redactor.withSecret(token), true, sessionTools);
        URI uri = open(token, context);
        Runnable release = () -> {
            forget(token);
            delete(configFile);
            delete(settingsFile);
            delete(childGateFile);
        };
        Optional<Path> childGate = (context.readOnlyChildren() || rootTools(context))
                ? Optional.of(childGateFile) : Optional.empty();
        try {
            Files.createDirectories(providerDirectory);
            // Claude's HTTP first-response deadline otherwise expires after 60 seconds, before the approval card.
            String json = objectMapper.writeValueAsString(new McpConfiguration(ImmutableMap.of(
                    toolServer.name(),
                    new McpServer("http", uri.toString(), ImmutableMap.of("Authorization", "Bearer " + token), AgentApprovalHandler.WAIT.plusSeconds(30).toMillis(), true))));
            writeOwnerOnly(configFile, json);
            if (childGate.isPresent()) {
                writeOwnerOnly(childGateFile, childGateScript(context));
                Files.setPosixFilePermissions(childGateFile, PosixFilePermissions.fromString("rwx------"));
            }
            writeOwnerOnly(settingsFile, sandbox.settings(context.scope(), runtimeDirectory.toRealPath(), worktree, environment, childGate));
            List<ToolDefinition> exposed = exposedTools(context);
            List<String> toolNames = exposed.stream()
                    .map(ToolDefinition::name)
                    .filter(name -> !name.equals("permission_prompt"))
                    .collect(toImmutableList());
            return new McpRegistration(
                    token, uri, toolNames, toolsDigest(exposed), rootTools(context), managedInstruction(context),
                    Optional.of(configFile), Optional.of(settingsFile), release);
        }
        catch (IOException e) {
            release.run();
            throw new AgentUnavailableException("Unable to write Claude MCP configuration", e);
        }
        catch (RuntimeException failure) {
            release.run();
            throw failure;
        }
    }

    /**
     * Registers a token for a provider that reaches this endpoint through its own MCP
     * configuration, exposing the session's tools. Empty when the session has none.
     */

    public Optional<McpRegistration> registerTools(
            String taskId, Path worktree, AgentPermissionScope scope, AgentApprovalHandler approvals, SecretRedactor redactor,
            List<Tool> sessionTools)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(redactor, "redactor is null");
        String token = token();
        PermissionContext context = resolveContext(taskId, worktree, scope, approvals, redactor.withSecret(token), false, sessionTools);
        if (tools(context).isEmpty()) {
            return Optional.empty();
        }
        URI uri = open(token, context);
        List<ToolDefinition> exposed = exposedTools(context);
        return Optional.of(new McpRegistration(
                token, uri, exposed.stream().map(ToolDefinition::name).collect(toImmutableList()), toolsDigest(exposed),
                rootTools(context), managedInstruction(context),
                Optional.empty(), Optional.empty(), () -> forget(token)));
    }

    private synchronized URI open(String token, PermissionContext context)
    {
        if (server.isEmpty()) {
            server = Optional.of(McpLoopbackServer.start(this, objectMapper));
        }
        contexts.put(token, context);
        return server.orElseThrow().endpoint();
    }

    private synchronized void forget(String token)
    {
        contexts.remove(token);
        nativeOnlyTokens.remove(token);
        if (contexts.isEmpty()) {
            close();
        }
    }

    @Override
    public synchronized void close()
    {
        server.ifPresent(McpLoopbackServer::close);
        server = Optional.empty();
    }

    /** Fresh turn authority is reachable only from an already validated native root call. */
    public void nativeOnly(McpRegistration registration)
    {
        checkArgument(contexts.containsKey(registration.token()), "Task token is not registered");
        nativeOnlyTokens.add(registration.token());
    }

    /** Keeps the children's shared MCP token to the tools marked for them; the root receives the others through App Server. */
    public CodexRootTools guardCodexRootTools(McpRegistration registration)
    {
        requireNonNull(registration, "registration is null");
        PermissionContext context = requireNonNull(contexts.get(registration.token()), "task token is not registered");
        checkArgument(rootTools(context), "No root-bound tools on this task");
        Set<String> readable = childCallableNames(context).collect(ImmutableSet.toImmutableSet());
        // Shared HTTP requests carry no native turn identity; all mutating capabilities stay on the root dispatcher.
        List<String> mcpNames = tools(context).stream().map(ToolDefinition::name)
                .filter(readable::contains)
                .collect(toImmutableList());
        mcpNames = ImmutableList.<String>builder().addAll(mcpNames).add(toolServer.listTools(), toolServer.getTool()).build();
        // Codex persists this schema for the conversation. Concrete tools and their schemas are discovered at call time.
        ToolDefinition dispatcher = callToolDefinition();
        List<Map<String, Object>> rootTools = ImmutableList.of(ImmutableMap.of(
                "type", "function", "name", dispatcher.name(), "description", dispatcher.description(), "inputSchema", dispatcher.inputSchema()));
        CodexRootTools guarded = new CodexRootTools(mcpNames, rootTools);
        context.codexReviewTools().set(guarded);
        return guarded;
    }

    /** Calls one withheld task tool after App Server has proven its caller is the root review turn. */
    public McpHttpResponse callCodexRootTool(String token, String name, JsonNode arguments)
    {
        PermissionContext context = contexts.get(requireNonNull(token, "token is null"));
        if (context == null || !name.equals(toolServer.callTool()) || arguments == null || !arguments.isObject()) {
            return error(null, -32602, "Unknown tool or invalid arguments");
        }
        try {
            return callTool(new McpRequest("2.0", objectMapper.valueToTree(1), "tools/call", objectMapper.valueToTree(new ToolCallParams(name, arguments))), context);
        }
        catch (JsonProcessingException | RuntimeException failure) {
            return error(null, -32603, "Task tool failed");
        }
    }

    public record CodexRootTools(List<String> mcpNames, List<Map<String, Object>> rootTools)
    {
        public CodexRootTools
        {
            mcpNames = ImmutableList.copyOf(mcpNames);
            rootTools = ImmutableList.copyOf(rootTools);
        }
    }

    private PermissionContext resolveContext(
            String taskId,
            Path worktree,
            AgentPermissionScope scope,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            boolean permissionTools,
            List<Tool> sessionTools)
    {
        // Claude's gate admits read-only children through this flag. Codex registers no permission tools and
        // gates its children through its own launch-time PreToolUse hook.
        boolean readOnlyChildren = permissionTools && scope.readOnlySubagents() && !scope.noSubagents();
        return new PermissionContext(
                taskId, worktree, scope, approvals, redactor, permissionTools, sessionTools, readOnlyChildren, new AtomicReference<>());
    }

    /** Combines the fixed capabilities' guidance for the session's instruction channel. */
    private Optional<String> managedInstruction(PermissionContext context)
    {
        return discovery(context) && tools(context).stream().anyMatch(tool -> !tool.name().equals("permission_prompt"))
                ? Optional.of(TOOL_DISCOVERY_INSTRUCTION.replace("@SERVER@", toolServer.name()).replace("@LIST@", toolServer.listTools())
                        .replace("@GET@", toolServer.getTool()).replace("@CALL@", toolServer.callTool()))
                : Optional.empty();
    }

    /**
     * Handles one authenticated JSON-RPC request from Claude's MCP client.
     *
     * @param bearerToken task capability token from the HTTP Authorization header
     * @param body JSON-RPC request body
     * @return HTTP status and optional JSON response body
     */
    public McpHttpResponse handle(String bearerToken, JsonNode body)
    {
        requireNonNull(bearerToken, "bearerToken is null");
        requireNonNull(body, "body is null");
        PermissionContext context = contexts.get(bearerToken);
        if (context == null || nativeOnlyTokens.contains(bearerToken)) {
            return new McpHttpResponse(401, Optional.empty());
        }
        CodexRootTools guarded = context.codexReviewTools().get();
        try {
            McpRequest request = objectMapper.treeToValue(body, McpRequest.class);
            return switch (request.method()) {
                case "initialize" -> response(request.id(), new InitializeResult(
                        protocolVersion(request.params()),
                        new ServerCapabilities(new ToolCapabilities(false)),
                        new ServerInfo(toolServer.name(), "1")));
                case "notifications/initialized" -> new McpHttpResponse(202, Optional.empty());
                case "ping" -> response(request.id(), ImmutableMap.of());
                case "tools/list" -> response(request.id(), new ToolsResult(exposedTools(context).stream()
                        .filter(tool -> guarded == null || guarded.mcpNames().contains(tool.name()))
                        .collect(toImmutableList())));
                case "tools/call" -> {
                    String name = request.params().path("name").asText("");
                    if (guarded != null && !guarded.mcpNames().contains(name)) {
                        yield error(request.id(), -32602, "Unknown tool");
                    }
                    yield callTool(request, context);
                }
                default -> error(request.id(), -32601, "Method not found");
            };
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            return error(null, -32600, "Invalid request");
        }
    }

    private McpHttpResponse callTool(McpRequest request, PermissionContext context)
            throws JsonProcessingException
    {
        ToolCallParams params = objectMapper.treeToValue(request.params(), ToolCallParams.class);
        // The one authorization gate: a tool runs only when this task offers it, so no handler can be reached by name alone.
        if (exposedTools(context).stream().noneMatch(tool -> tool.name().equals(params.name()))) {
            return error(request.id(), -32602, "Unknown tool");
        }
        String name = params.name();
        if (name.equals(toolServer.listTools()) || name.equals(toolServer.getTool())) {
            return describeTools(request.id(), params, context);
        }
        if (name.equals(toolServer.callTool())) {
            return callDiscoveredTool(request.id(), params.arguments(), context);
        }
        return name.equals("permission_prompt") ? permission(request.id(), params.arguments(), context) : sessionTool(request.id(), params, context);
    }

    private McpHttpResponse describeTools(JsonNode id, ToolCallParams params, PermissionContext context)
            throws JsonProcessingException
    {
        JsonNode arguments = params.arguments();
        boolean list = params.name().equals(toolServer.listTools());
        if (!arguments.isObject() || (list ? !arguments.isEmpty() : arguments.size() != 1 || !arguments.path("name").isTextual())) {
            return error(id, -32602, list ? toolServer.listTools() + " accepts no arguments" : toolServer.getTool() + " requires name");
        }
        List<ToolDefinition> available = tools(context).stream().filter(tool -> !tool.name().equals("permission_prompt")).collect(toImmutableList());
        Object result;
        if (list) {
            result = available.stream().map(tool -> ImmutableMap.of("name", tool.name(), "description", tool.description())).collect(toImmutableList());
        }
        else {
            Optional<ToolDefinition> tool = available.stream().filter(value -> value.name().equals(arguments.path("name").asText())).findFirst();
            if (tool.isEmpty()) {
                return error(id, -32602, "Unknown tool in this task. Use " + toolServer.listTools() + " to discover available tools");
            }
            result = tool.orElseThrow();
        }
        return toolResponse(id, context.redactor().redact(objectMapper.writeValueAsString(result)), false);
    }

    private McpHttpResponse callDiscoveredTool(JsonNode id, JsonNode arguments, PermissionContext context)
            throws JsonProcessingException
    {
        if (!arguments.isObject() || arguments.size() != 2 || !arguments.path("name").isTextual() || !arguments.path("arguments").isObject()) {
            return error(id, -32602, toolServer.callTool() + " requires name and an arguments object. Read " + toolServer.getTool() + " for the tool's parameters");
        }
        String name = arguments.path("name").asText();
        // Only concrete capabilities in this task can run; discovery, permission callbacks and recursive dispatch cannot.
        if (name.equals("permission_prompt") || tools(context).stream().noneMatch(tool -> tool.name().equals(name))) {
            return error(id, -32602, "Unknown tool in this task. Use " + toolServer.listTools() + " to discover available tools");
        }
        return callTool(new McpRequest("2.0", id, "tools/call", arguments), context);
    }

    /**
     * Runs one session tool for the task the token names. Validation failures come back as tool
     * text the model can repair from; an unexpected failure is an error result, never a thrown
     * exception that would drop the provider's connection.
     */
    private McpHttpResponse sessionTool(JsonNode id, ToolCallParams params, PermissionContext context)
            throws JsonProcessingException
    {
        Optional<Tool> tool = context.sessionTools().stream().filter(value -> value.name().equals(params.name())).findFirst();
        if (tool.isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        JsonNode arguments = params.arguments().isObject() ? redacted(params.arguments().deepCopy(), context.redactor()) : objectMapper.createObjectNode();
        String json = objectMapper.writeValueAsString(arguments);
        String text;
        boolean failed = false;
        try {
            text = tool.orElseThrow().call(json);
        }
        catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            text = failureReason(failure);
            failed = true;
        }
        return toolResponse(id, context.redactor().redact(text), failed);
    }

    private McpHttpResponse permission(JsonNode id, JsonNode input, PermissionContext context)
            throws JsonProcessingException
    {
        PermissionArguments arguments = objectMapper.treeToValue(input, PermissionArguments.class);
        Optional<String> refused = Optional.empty();
        if (context.scope().access() == SessionAccess.TOOLS_ONLY) {
            // Its host tools need no approval, and it has no others; whatever asks is outside what the session was given.
            refused = Optional.of("This session has nothing to approve");
        }
        else if (context.scope().readOnlySubagents()) {
            if (arguments.toolName().equalsIgnoreCase("Agent") || arguments.toolName().equalsIgnoreCase("Task")) {
                boolean explore = Optional.ofNullable(arguments.input().get("subagent_type")).filter(JsonNode::isTextual).map(JsonNode::asText)
                        .filter("Explore"::equals).isPresent();
                if (!context.readOnlyChildren()) {
                    refused = Optional.of("This session starts no subagents; its tools are for the main agent");
                }
                else if (!explore) {
                    refused = Optional.of("This session's subagents only read, so it may start only Explore subagents, for searching and exploring");
                }
            }
            else if (arguments.toolName().equalsIgnoreCase("Skill")
                    || arguments.toolName().equalsIgnoreCase("Workflow")
                    || arguments.toolName().equalsIgnoreCase("SendMessage")) {
                refused = Optional.of("This session's subagents only read; skills, workflows and messages to subagents would start or steer other kinds");
            }
        }
        if (refused.isPresent()) {
            // Refused before any card exists: review authority is not extended by a tool approval.
            PermissionResult denied = new PermissionResult("deny", Optional.empty(), refused);
            return toolResponse(id, objectMapper.writeValueAsString(denied), false);
        }
        boolean bash = arguments.toolName().equalsIgnoreCase("Bash");
        boolean unsandboxed = bash && (Optional.ofNullable(arguments.input().get("dangerouslyDisableSandbox"))
                .filter(JsonNode::isBoolean).map(JsonNode::asBoolean).orElse(false)
                // Claude runs these outside the sandbox with no flag. Any command that starts like one is shown that way, since
                // a card that says sandboxed may be approved by the Assistant in words.
                || ClaudeSandboxSettings.unsandboxedCommand(context.scope()).matcher(arguments.input().path("command").asText("")).lookingAt());
        Optional<PermissionResult> allowed = precheckedFileEditPermission(arguments, context);
        if (allowed.isPresent()) {
            return toolResponse(id, objectMapper.writeValueAsString(allowed.orElseThrow()), false);
        }
        String detail = bounded(context.redactor().redact(objectMapper.writeValueAsString(arguments.input())));
        if (unsandboxed) {
            detail = "Runs outside Claude's filesystem sandbox with host file access.\n" + detail;
        }
        ProviderApprovalRequest approval = new ProviderApprovalRequest(
                kind(arguments.toolName()),
                unsandboxed ? "Allow Claude unsandboxed command" : "Allow Claude " + arguments.toolName(),
                detail,
                ImmutableList.of(arguments.toolName()),
                ImmutableList.of(ALLOW_ONCE, DENY),
                // Claude's sandbox covers only its shell, and that sandbox fails closed rather than running unsandboxed.
                Optional.empty(),
                bash && !unsandboxed,
                root(),
                Instant.now(),
                approvalScope(arguments),
                bash ? Optional.ofNullable(arguments.input().get("command")).filter(JsonNode::isTextual).map(JsonNode::asText).map(context.redactor()::redact)
                        : Optional.empty());
        ProviderApprovalDecision decision = awaitApproval(approval, context);

        PermissionResult result = decision.decision() == ProviderApprovalDecision.Decision.ALLOW
                ? new PermissionResult("allow", Optional.of(answered(arguments, decision.answers())), Optional.empty())
                : new PermissionResult("deny", Optional.empty(), decision.reason().or(() -> Optional.of(decision.timedOut() ? "Approval timed out" : "Denied by user")));
        String text = objectMapper.writeValueAsString(result);
        return toolResponse(id, text, false);
    }

    private static Optional<ProviderApprovalScope> approvalScope(PermissionArguments arguments)
    {
        if (arguments.toolName().equals("Bash")) {
            return Optional.ofNullable(arguments.input().get("command")).filter(JsonNode::isTextual).map(JsonNode::asText).flatMap(ApprovalScopes::githubRead);
        }
        if (arguments.toolName().equals("WebFetch")) {
            return Optional.ofNullable(arguments.input().get("url")).filter(JsonNode::isTextual).map(JsonNode::asText).flatMap(ApprovalScopes::webFetch);
        }
        return Optional.empty();
    }

    private static ProviderApprovalDecision awaitApproval(ProviderApprovalRequest approval, PermissionContext context)
    {
        try {
            return context.approvals()
                    .request(approval, AgentApprovalHandler.WAIT)
                    .toCompletableFuture()
                    .get(AgentApprovalHandler.WAIT.plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderApprovalDecision.deny();
        }
        catch (ExecutionException e) {
            return ProviderApprovalDecision.deny();
        }
        catch (TimeoutException e) {
            return ProviderApprovalDecision.expired();
        }
    }

    /**
     * Claude's question tool reads the user's choices back from its own input, keyed by the exact question asked.
     * Only answers to questions that were asked are carried, because Claude drops an edited input as a whole and
     * would then have no answer at all.
     */
    private static JsonNode answered(PermissionArguments arguments, Map<String, String> answers)
    {
        JsonNode input = arguments.input();
        if (answers.isEmpty() || !arguments.toolName().equals(QUESTION_TOOL)
                || !input.isObject() || input.has("answers") || !input.path("questions").isArray()) {
            return input;
        }
        ObjectNode accepted = ((ObjectNode) input).objectNode();
        for (JsonNode question : input.get("questions")) {
            String asked = question.path("question").asText("");
            String answer = answers.getOrDefault(asked, "");
            if (!answer.isBlank() && answer.length() <= MAX_ANSWER_CHARACTERS) {
                accepted.put(asked, answer);
            }
        }
        if (accepted.isEmpty()) {
            return input;
        }
        return ((ObjectNode) input.deepCopy()).set("answers", accepted);
    }

    // A task tool may store or publish what the agent sends, so the provider's own credentials never reach it.
    private static JsonNode redacted(JsonNode node, SecretRedactor redactor)
    {
        if (node.isTextual()) {
            return TextNode.valueOf(redactor.redact(node.textValue()));
        }
        if (node instanceof ObjectNode object) {
            object.properties().forEach(field -> field.setValue(redacted(field.getValue(), redactor)));
        }
        else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, redacted(array.get(index), redactor));
            }
        }
        return node;
    }

    // ponytail: parses the schema on every listing; cache it per tool if tool listings show up in profiles.
    private static Map<String, Object> schema(Tool tool)
    {
        try {
            return SCHEMAS.readValue(tool.inputSchema(), SCHEMA_TYPE);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Tool schema is not JSON: " + tool.name(), failure);
        }
    }

    private static String failureReason(Exception failure)
    {
        return Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
    }

    private McpHttpResponse toolResponse(JsonNode id, String text, boolean failed)
    {
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), failed));
    }

    private McpHttpResponse response(JsonNode id, Object result)
    {
        return new McpHttpResponse(200, Optional.of(objectMapper.valueToTree(new McpResponse("2.0", id, result))));
    }

    private McpHttpResponse error(JsonNode id, int code, String message)
    {
        return new McpHttpResponse(200, Optional.of(objectMapper.valueToTree(new McpErrorResponse("2.0", id, new McpError(code, message)))));
    }

    private String protocolVersion(JsonNode params)
            throws JsonProcessingException
    {
        return objectMapper.treeToValue(params, InitializeParams.class).protocolVersion();
    }


    private static ToolDefinition permissionTool()
    {
        return new ToolDefinition(
                "permission_prompt",
                "Ask the user to approve a Claude tool call",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of("tool_name", ImmutableMap.of("type", "string"), "input", ImmutableMap.of("type", "object")),
                        "required", ImmutableList.of("tool_name", "input"),
                        "additionalProperties", true));
    }

    /** A digest of the tools a session is given, by name, description and parameters in any order, so two setups can be told apart. */
    private String toolsDigest(List<ToolDefinition> tools)
    {
        try {
            String canonical = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(tools.stream().sorted(comparing(ToolDefinition::name)).collect(toImmutableList()));
            return Hashing.sha256().hashString(canonical, StandardCharsets.UTF_8).toString();
        }
        catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A tools-only session calls its few tools by name: the catalog would add two calls to each and a second way to reach them. */
    private static boolean discovery(PermissionContext context)
    {
        return context.scope().access() != SessionAccess.TOOLS_ONLY;
    }

    private List<ToolDefinition> exposedTools(PermissionContext context)
    {
        List<ToolDefinition> available = tools(context);
        if (!discovery(context) || available.stream().noneMatch(tool -> !tool.name().equals("permission_prompt"))) {
            return available;
        }
        return ImmutableList.<ToolDefinition>builder().addAll(available)
                .add(new ToolDefinition(toolServer.listTools(),
                        "List this task's current " + toolServer.name() + " tool names and descriptions. Choose relevant tools, then read " + toolServer.getTool()
                                + " for usage and parameters",
                        ToolSchemas.object(ImmutableMap.of())))
                .add(new ToolDefinition(toolServer.getTool(),
                        "Read one task tool's current description and complete inputSchema, including required parameters and allowed values. Does not run the tool",
                        ToolSchemas.object(ImmutableMap.of("name", ImmutableMap.of("type", "string", "minLength", 1)), "name")))
                .add(callToolDefinition()).build();
    }

    private ToolDefinition callToolDefinition()
    {
        return new ToolDefinition(toolServer.callTool(),
                "Run one current " + toolServer.name() + " task tool. Discover names with " + toolServer.listTools() + " and read usage and parameters with "
                        + toolServer.getTool() + " first. "
                        + "Pass its name and arguments object. Existing permissions apply; review and related-work changes belong to the main agent",
                ToolSchemas.object(ImmutableMap.of(
                        "name", ImmutableMap.of("type", "string", "minLength", 1),
                        "arguments", ImmutableMap.of("type", "object", "additionalProperties", true)), "name", "arguments"));
    }

    private static List<ToolDefinition> tools(PermissionContext context)
    {
        ImmutableList.Builder<ToolDefinition> tools = ImmutableList.builder();
        if (context.permissionTools()) {
            tools.add(permissionTool());
        }
        context.sessionTools().forEach(tool -> tools.add(new ToolDefinition(tool.name(), tool.description(), schema(tool))));
        return tools.build();
    }

    private static Optional<PermissionResult> precheckedFileEditPermission(PermissionArguments arguments, PermissionContext context)
    {
        Optional<Path> requested = requestedPath(arguments, context.worktree());
        if (requested.isPresent() && AgentPermissionManager.allowsFileWrite(
                requested.orElseThrow(), context.worktree(), context.scope())) {
            return Optional.of(new PermissionResult("allow", Optional.of(arguments.input()), Optional.empty()));
        }
        return Optional.empty();
    }

    private static Optional<Path> requestedPath(PermissionArguments arguments, Path worktree)
    {
        String field;
        if (arguments.toolName().equalsIgnoreCase("Edit") || arguments.toolName().equalsIgnoreCase("Write")) {
            field = "file_path";
        }
        else if (arguments.toolName().equalsIgnoreCase("NotebookEdit")) {
            field = "notebook_path";
        }
        else {
            return Optional.empty();
        }
        JsonNode value = arguments.input().get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value.asText());
            return Optional.of((path.isAbsolute() ? path : worktree.resolve(path)).toAbsolutePath().normalize());
        }
        catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /**
     * How the agent must run the session's unsandboxed commands for Claude to exempt them; empty when every command stays sandboxed.
     * Claude exempts only the command alone, optionally after {@code cd <dir> &&}; a pipe, redirect, chain, or subshell keeps it in.
     */
    public static String sandboxInstruction(AgentPermissionScope scope)
    {
        return ClaudeSandboxSettings.instruction(scope);
    }

    /** Fills the gate's allowlist with the tools that only read: a tool stays root-only until it is marked child-callable. */
    private String childGateScript(PermissionContext context)
    {
        Stream<String> readable = Stream.concat(
                (context.scope().readOnlySubagents() ? childCallableNames(context) : childReadable(context)).filter(name -> SHELL_SAFE_TOOL_NAME.matcher(name).matches()),
                Stream.of(toolServer.listTools(), toolServer.getTool()));
        return CHILD_GATE_SCRIPT.replace("@REVIEW_ONLY@", String.valueOf(context.scope().readOnlySubagents()))
                .replace("@CHILD_TOOLS@", readable.map(toolServer::qualified).collect(Collectors.joining("|")));
    }

    private static Stream<String> childCallableNames(PermissionContext context)
    {
        return context.sessionTools().stream().filter(Tool::subagents).map(Tool::name);
    }

    /**
     * A task tool not marked for subagents is served only where the provider proves the root called it.
     * A restricted session's tools follow its access instead: a tools-only session starts no children at all.
     */
    private static boolean rootTools(PermissionContext context)
    {
        return !context.scope().access().restricted() && context.sessionTools().stream().anyMatch(tool -> !tool.subagents());
    }

    /** Without a review, a child may call every tool except the operations kept for the root. */
    private static Stream<String> childReadable(PermissionContext context)
    {
        Set<String> rootOnly = context.sessionTools().stream().filter(tool -> !tool.subagents()).map(Tool::name).collect(ImmutableSet.toImmutableSet());
        return tools(context).stream().map(ToolDefinition::name).filter(name -> !rootOnly.contains(name));
    }

    private static String readChildGateScript()
    {
        try {
            return Resources.toString(Resources.getResource(PermissionBroker.class, "review-child-gate.sh"), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot load the review child gate", e);
        }
    }

    private static void writeOwnerOnly(Path path, String content)
            throws IOException
    {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private static void delete(Path path)
    {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // Task resource cleanup owns the final deletion retry.
        }
    }

    private static ProviderApprovalRequest.Kind kind(String toolName)
    {
        if (toolName.equalsIgnoreCase("Bash")) {
            return ProviderApprovalRequest.Kind.COMMAND;
        }
        if (toolName.equalsIgnoreCase("Edit") || toolName.equalsIgnoreCase("Write")) {
            return ProviderApprovalRequest.Kind.FILE_CHANGE;
        }
        return ProviderApprovalRequest.Kind.TOOL;
    }

    private static String token()
    {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String bounded(String text)
    {
        return text.length() <= MAX_DETAIL_CHARACTERS
                ? text
                : text.substring(0, MAX_DETAIL_CHARACTERS);
    }

    private record PermissionContext(
            String taskId,
            Path worktree,
            AgentPermissionScope scope,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            boolean permissionTools,
            List<Tool> sessionTools,
            boolean readOnlyChildren,
            AtomicReference<CodexRootTools> codexReviewTools)
    {
        private PermissionContext
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(worktree, "worktree is null");
            requireNonNull(scope, "scope is null");
            requireNonNull(approvals, "approvals is null");
            requireNonNull(redactor, "redactor is null");
            requireNonNull(codexReviewTools, "codexReviewTools is null");
            sessionTools = ImmutableList.copyOf(sessionTools);
        }
    }

    private record McpConfiguration(@JsonProperty("mcpServers") Map<String, McpServer> servers)
    {
        private McpConfiguration
        {
            requireNonNull(servers, "servers is null");
            servers = ImmutableMap.copyOf(servers);
        }
    }

    /**
     * {@code alwaysLoad} keeps every host tool in the prompt. Claude defers MCP tools behind tool
     * search by default, and a deferred {@code report_development_result} is one the agent never finds
     * and never calls, which fails the turn's mandatory report.
     */
    private record McpServer(String type, String url, Map<String, String> headers, long timeout, boolean alwaysLoad)
    {
        private McpServer
        {
            requireNonNull(type, "type is null");
            requireNonNull(url, "url is null");
            requireNonNull(headers, "headers is null");
            headers = ImmutableMap.copyOf(headers);
        }
    }

    private record McpRequest(String jsonrpc, JsonNode id, String method, JsonNode params)
    {
        private McpRequest
        {
            requireNonNull(jsonrpc, "jsonrpc is null");
            requireNonNull(method, "method is null");
        }
    }

    private record InitializeParams(String protocolVersion)
    {
        private InitializeParams
        {
            requireNonNull(protocolVersion, "protocolVersion is null");
        }
    }

    private record InitializeResult(
            String protocolVersion,
            ServerCapabilities capabilities,
            ServerInfo serverInfo) {}

    private record ServerCapabilities(ToolCapabilities tools) {}

    private record ToolCapabilities(boolean listChanged) {}

    private record ServerInfo(String name, String version) {}

    private record ToolsResult(List<ToolDefinition> tools) {}

    private record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {}

    private record ToolCallParams(String name, JsonNode arguments)
    {
        private ToolCallParams
        {
            requireNonNull(name, "name is null");
            requireNonNull(arguments, "arguments is null");
        }
    }

    private record PermissionArguments(@JsonProperty("tool_name") String toolName, JsonNode input)
    {
        private PermissionArguments
        {
            requireNonNull(toolName, "toolName is null");
            requireNonNull(input, "input is null");
        }
    }

    private record PermissionResult(
            String behavior,
            @JsonProperty("updatedInput") Optional<JsonNode> updatedInput,
            Optional<String> message) {}

    private record ToolResult(List<TextContent> content, @JsonProperty("isError") boolean error) {}

    private record TextContent(String type, String text) {}

    private record McpResponse(String jsonrpc, JsonNode id, Object result) {}

    private record McpErrorResponse(String jsonrpc, JsonNode id, McpError error) {}

    private record McpError(int code, String message) {}
}

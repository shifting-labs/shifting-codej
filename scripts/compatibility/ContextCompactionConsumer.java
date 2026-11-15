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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.shiftinglabs.codej.Access;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.ClaudeApi;
import io.github.shiftinglabs.codej.DeepSeekApi;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.TurnResult;
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactor;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompaction;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Candidate-only consumer: private replay record binding, local compaction and resume with JDK + jar. */
public final class ContextCompactionConsumer
{
    public static void main(String[] args)
            throws Exception
    {
        for (boolean claude : new boolean[] {true, false}) {
            Path root = Path.of(args[0]).resolve(claude ? "claude" : "deepseek");
            Files.createDirectories(root);
            try (Fixture fixture = new Fixture(claude, root)) {
                AtomicInteger proposals = new AtomicInteger();
                ContextCompactor policy = context -> {
                    require(context.history().complete() && !context.cancelled(), "Invalid compaction callback state");
                    require(context.replayCharacters() > 0, "Missing context size");
                    if (context.history().exchanges().size() < 2) {
                        return Optional.empty();
                    }
                    require(((ModelInput.UserMessage) context.history().exchanges().getFirst().input()).text().contains("original audit"), "Audit prefix was removed");
                    proposals.incrementAndGet();
                    return Optional.of(new ModelCompaction("packaged summary", 1));
                };
                SessionRef saved;
                var options = SessionOptions.in(root).access(Access.TOOLS_ONLY).build();
                try (Agent agent = fixture.agent(Optional.of(policy)); Session session = agent.startSession(options)) {
                    completed(session, "original audit " + "x".repeat(8000));
                    completed(session, "recent input");
                    completed(session, "current input");
                    saved = session.ref().orElseThrow();
                }
                try (Agent agent = fixture.agent(Optional.of(policy)); Session session = agent.resumeSession(saved, options)) {
                    completed(session, "after restart");
                }
                try (Agent agent = fixture.agent(Optional.empty()); Session session = agent.resumeSession(saved, options)) {
                    completed(session, "without policy");
                }
                require(proposals.get() == 2 && fixture.requests.get() == 5, "Hidden inference or incorrect compaction count");
                require(fixture.failure.get() == null, "Wire check failed");
                String body = fixture.lastBody.get();
                require(body.contains("packaged summary") && body.contains("current input") && body.contains("after restart")
                        && body.contains("without policy") && !body.contains("original audit") && !body.contains("recent input"), "Incorrect compacted context");
            }
        }
        System.out.println("PASS: local Claude/DeepSeek context compaction, complete audit prefix, repeated rewrites and durable resume; JDK + jar only");
    }

    static void completed(Session session, String input)
            throws InterruptedException
    {
        require(session.run(Input.text(input)) instanceof TurnResult.Completed, "Compacted session turn failed");
    }

    static final class Fixture
            implements AutoCloseable
    {
        final boolean claude;
        final Path root;
        final String model;
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean commandRequested = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.List<String> requestBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicReference<String> lastBody = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Fixture(boolean claude, Path root)
                throws IOException
        {
            this(claude, root, claude ? "claude-contract" : "deepseek-chat");
        }

        Fixture(boolean claude, Path root, String model)
                throws IOException
        {
            this.claude = claude;
            this.root = root;
            this.model = model;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        Agent agent(Optional<ContextCompactor> policy)
        {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            if (claude) {
                var builder = ClaudeApi.builder().model(model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                        .profileDirectory(root.resolve("profile")).stateDirectory(root.resolve("state")).requestTimeout(Duration.ofSeconds(5));
                policy.ifPresent(builder::contextCompactor);
                return builder.build();
            }
            var builder = DeepSeekApi.builder().model(model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                    .profileDirectory(root.resolve("profile")).stateDirectory(root.resolve("state")).requestTimeout(Duration.ofSeconds(5));
            policy.ifPresent(builder::contextCompactor);
            return builder.build();
        }

        private void handle(HttpExchange exchange)
                throws IOException
        {
            try (exchange) {
                try {
                    require("Bearer sk-context-packaged".equals(exchange.getRequestHeaders().getFirst("Authorization")), "Wrong credential");
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    boolean metadata = exchange.getRequestMethod().equals("GET");
                    String body;
                    if (metadata) {
                        String info = "{\"id\":\"" + model + "\",\"type\":\"model\",\"display_name\":\"Contract\",\"created_at\":\"2026-11-13T00:00:00Z\"}";
                        body = claude ? exchange.getRequestURI().getPath().endsWith("/models")
                                ? "{\"data\":[" + info + "],\"has_more\":false,\"first_id\":\"" + model + "\",\"last_id\":\"" + model + "\"}" : info
                                : "{\"object\":\"list\",\"data\":[{\"id\":\"" + model + "\",\"object\":\"model\",\"owned_by\":\"deepseek\"}]}";
                    }
                    else {
                        requests.incrementAndGet();
                        require(!request.contains("sk-context-packaged"), "Credential entered request body");
                        lastBody.set(request);
                        requestBodies.add(request);
                        body = claude ? event("message_start", "\"message\":{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + model
                                + "\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}")
                                + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Answer\"}")
                                + event("content_block_stop", "\"index\":0")
                                + event("message_delta", "\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}")
                                + event("message_stop", "")
                                : "data: {\"id\":\"completion-1\",\"object\":\"chat.completion.chunk\",\"model\":\"" + model
                                + "\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Answer\"},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\ndata: [DONE]\n\n";
                    }
                    if (!metadata && commandRequested.compareAndSet(true, false)) {
                        String arguments = "{\"argv\":[\"/usr/bin/true\"],\"directory\":\".\",\"timeoutSeconds\":1}";
                        body = claude ? event("message_start", "\"message\":{\"id\":\"msg-command\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + model
                                + "\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}")
                                + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"command-1\",\"name\":\"run_command\",\"input\":" + arguments + "}")
                                + event("content_block_stop", "\"index\":0")
                                + event("message_delta", "\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}")
                                + event("message_stop", "")
                                : "data: {\"id\":\"command-1\",\"object\":\"chat.completion.chunk\",\"model\":\"" + model
                                + "\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"command-1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"run_command\",\"arguments\":\"" + arguments.replace("\"", "\\\"")
                                + "\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\ndata: [DONE]\n\n";
                    }
                    exchange.getResponseHeaders().add("Content-Type", metadata ? "application/json" : "text/event-stream");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    exchange.sendResponseHeaders(500, -1);
                }
            }
        }

        @Override
        public void close() { server.stop(0); }
    }

    private static String event(String type, String fields)
    {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}\n\n";
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

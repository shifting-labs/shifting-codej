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
import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.Decision;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.TurnResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Compiled against the candidate, then run with only the JDK and its shaded jar. No live service or credentials. */
public final class ClaudeApiConsumer
{
    private static boolean coding;
    private static final String MODEL = "claude-contract";
    private static final AtomicReference<String> KEY = new AtomicReference<>("sk-packaged-first");
    private static final AtomicReference<Throwable> SERVER_FAILURE = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger TOOLS = new AtomicInteger();

    public static void main(String[] args)
            throws Exception
    {
        Path state = Path.of(args[0]);
        coding = args.length > 1 && args[1].equals("coding");
        Path workspace = coding ? Files.createDirectories(state.resolve("workspace")) : state;
        if (coding) {
            Files.writeString(workspace.resolve("source.txt"), "tool-value");
        }
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var callbacks = Executors.newSingleThreadExecutor()) {
            http.setExecutor(callbacks);
            http.createContext("/", ClaudeApiConsumer::handle);
            http.start();
            URI endpoint = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
            Tool lookup = Tool.of("lookup", "A host lookup", Lookup.class, value -> {
                require(value.key().equals("value"), "Tool arguments changed");
                TOOLS.incrementAndGet();
                return "tool-value";
            });
            var sessionOptions = SessionOptions.in(workspace).access(coding ? Access.READ_ONLY : Access.TOOLS_ONLY)
                    .approvals(approval -> CompletableFuture.completedStage(Decision.ALLOW_ONCE));
            if (!coding) {
                sessionOptions.tool(lookup);
            }
            SessionOptions options = sessionOptions.build();
            SessionRef saved;
            try (Agent agent = agent(endpoint, state); Session session = agent.startSession(options)) {
                require(agent.models().size() == 1, "Model metadata failed");
                require(session.run(Input.text("Look up value")) instanceof TurnResult.Completed, "Tool turn failed");
                saved = session.ref().orElseThrow();
            }
            KEY.set("sk-packaged-rotated");
            try (Agent agent = agent(endpoint, state); Session session = agent.resumeSession(SessionRef.parse(saved.asString()), options)) {
                require(session.run(Input.text("Continue")) instanceof TurnResult.Completed, "Resume failed");
            }
            require(SERVER_FAILURE.get() == null, "Wire assertion failed: " + SERVER_FAILURE.get());
            require(TOOLS.get() == (coding ? 0 : 1) && REQUESTS.get() == 3, "Unexpected inference or tool count");
            if (coding) {
                System.out.println("PASS: shaded coding tools, record binding, contained commands, native process resources, file reads and resume; JDK + jar only");
            }
            System.out.println("PASS: shaded ClaudeApi metadata, streaming, signed tool replay, key rotation, host tools and durable resume; JDK + jar only");
        }
        finally {
            http.stop(0);
        }
    }

    private static Agent agent(URI endpoint, Path state)
    {
        var builder = ClaudeApi.builder().model(MODEL).apiKey(KEY::get).baseUrl(endpoint).stateDirectory(state)
                .requestTimeout(Duration.ofSeconds(5)).maximumOutputTokens(128);
        if (coding) {
            builder.codingTools(CodingTools.defaults());
        }
        return builder.build();
    }

    private static void handle(HttpExchange exchange)
            throws IOException
    {
        try (exchange) {
            try {
                require(("Bearer " + KEY.get()).equals(exchange.getRequestHeaders().getFirst("Authorization")), "Incorrect credential");
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response;
                boolean metadata = exchange.getRequestMethod().equals("GET");
                if (metadata) {
                    String info = "{\"id\":\"" + MODEL + "\",\"type\":\"model\",\"display_name\":\"Contract Claude\",\"created_at\":\"2026-11-13T00:00:00Z\"}";
                    response = exchange.getRequestURI().getPath().endsWith("/models")
                            ? "{\"data\":[" + info + "],\"has_more\":false,\"first_id\":\"" + MODEL + "\",\"last_id\":\"" + MODEL + "\"}" : info;
                }
                else {
                    int inference = REQUESTS.incrementAndGet();
                    require(!request.contains("sk-packaged"), "Credential in request body");
                    if (inference == 1) {
                        require(request.contains("input_schema"), "Missing tool schema");
                        response = start()
                                + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"private\",\"signature\":\"signed-state\"}")
                                + event("content_block_stop", "\"index\":0")
                                + event("content_block_start", "\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\""
                                        + (coding ? "read_file\",\"input\":{\"path\":\"source.txt" : "lookup\",\"input\":{\"key\":\"value") + "\"}}")
                                + event("content_block_stop", "\"index\":1") + end("tool_use");
                    }
                    else {
                        require(request.contains("signed-state") && request.contains("tool_result") && request.contains("tool-value"), "Lossy tool replay");
                        if (inference == 3) {
                            require(request.contains("Continue") && request.contains("Answer"), "History missing on resume");
                        }
                        response = start() + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Answer\"}")
                                + event("content_block_stop", "\"index\":0") + end("end_turn");
                    }
                }
                exchange.getResponseHeaders().add("Content-Type", metadata ? "application/json" : "text/event-stream");
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            catch (Throwable failure) {
                SERVER_FAILURE.compareAndSet(null, failure);
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    private static String start()
    {
        return event("message_start", "\"message\":{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + MODEL
                + "\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":2,\"output_tokens\":0}}");
    }

    private static String end(String reason)
    {
        return event("message_delta", "\"delta\":{\"stop_reason\":\"" + reason + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}")
                + event("message_stop", "");
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

    public record Lookup(String key) {}
}

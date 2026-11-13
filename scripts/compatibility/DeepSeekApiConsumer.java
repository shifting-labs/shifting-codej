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
import io.github.shiftinglabs.codej.DeepSeekApi;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Compiled against the candidate, then run with only the JDK and its shaded jar. No live service or credentials. */
public final class DeepSeekApiConsumer
{
    private static final String MODEL = "deepseek-contract";
    private static final AtomicReference<String> KEY = new AtomicReference<>("sk-packaged-first");
    private static final AtomicReference<Throwable> SERVER_FAILURE = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger TOOLS = new AtomicInteger();

    public static void main(String[] args)
            throws Exception
    {
        Path state = Path.of(args[0]);
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var callbacks = Executors.newSingleThreadExecutor()) {
            http.setExecutor(callbacks);
            http.createContext("/", DeepSeekApiConsumer::handle);
            http.start();
            URI endpoint = URI.create("http://127.0.0.1:" + http.getAddress().getPort());
            Tool lookup = Tool.of("lookup", "A host lookup", Lookup.class, value -> {
                require(value.key().equals("value"), "Tool arguments changed");
                TOOLS.incrementAndGet();
                return "tool-value";
            });
            SessionOptions options = SessionOptions.in(state).access(Access.TOOLS_ONLY).tool(lookup)
                    .approvals(approval -> CompletableFuture.completedStage(Decision.ALLOW_ONCE)).build();
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
            require(TOOLS.get() == 1 && REQUESTS.get() == 3, "Unexpected inference or tool count");
            System.out.println("PASS: shaded DeepSeekApi metadata, streaming, reasoning/tool replay, key rotation, host tools and durable resume; JDK + jar only");
        }
        finally {
            http.stop(0);
        }
    }

    private static Agent agent(URI endpoint, Path state)
    {
        return DeepSeekApi.builder().model(MODEL).apiKey(KEY::get).baseUrl(endpoint).stateDirectory(state)
                .requestTimeout(Duration.ofSeconds(5)).maximumOutputTokens(128).build();
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
                    response = "{\"object\":\"list\",\"data\":[{\"id\":\"" + MODEL + "\",\"object\":\"model\",\"owned_by\":\"deepseek\"}]}";
                }
                else {
                    int inference = REQUESTS.incrementAndGet();
                    require(!request.contains("sk-packaged"), "Credential in request body");
                    if (inference == 1) {
                        require(request.contains("parameters"), "Missing tool schema");
                        String arguments = "{\"key\":\"value\"}";
                        String delta = "{\"role\":\"assistant\",\"reasoning_content\":\"private-reasoning\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                                + "\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":" + quote(arguments) + "}}]}";
                        response = chunk(delta, "tool_calls");
                    }
                    else {
                        require(request.contains("private-reasoning") && request.contains("tool_call_id") && request.contains("tool-value"), "Lossy tool replay");
                        if (inference == 3) {
                            require(request.contains("Continue") && request.contains("Answer"), "History missing on resume");
                        }
                        response = chunk("{\"role\":\"assistant\",\"content\":\"Answer\"}", "stop");
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

    private static String quote(String text)
    {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String chunk(String delta, String reason)
    {
        return "data: {\"id\":\"completion-1\",\"object\":\"chat.completion.chunk\",\"model\":\"" + MODEL
                + "\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":\"" + reason + "\"}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\ndata: [DONE]\n\n";
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public record Lookup(String key) {}
}

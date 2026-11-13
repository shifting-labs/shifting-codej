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
package io.github.shiftinglabs.codej.sdk.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Loopback Chat Completions with deterministic metadata, framing, errors and cancellation. */
public final class DeepSeekHttpFixture
        implements AutoCloseable
{
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final String MODEL = "deepseek-contract";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("deepseek-fixture-", 0).factory());
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final BlockingQueue<Boolean> waiting = new LinkedBlockingQueue<>();

    public DeepSeekHttpFixture()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI baseUrl() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    public List<Request> requests() { return List.copyOf(requests); }

    public void reply(int status, String body) { replies.add(new Reply(status, body)); }

    public void awaitWaiting()
            throws InterruptedException
    {
        if (waiting.poll(2, TimeUnit.SECONDS) == null) {
            throw new AssertionError("No pending DeepSeek HTTP stream");
        }
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        try (exchange) {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            JsonNode body = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()), body));
            Reply scripted = replies.poll();
            if (scripted != null) {
                if (scripted.status == 307) {
                    exchange.getResponseHeaders().add("Location", baseUrl().resolve("/redirect-target").toString());
                }
                send(exchange, scripted.status, scripted.body);
                return;
            }
            if (exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"" + MODEL + "\",\"object\":\"model\",\"owned_by\":\"deepseek\"}]}");
                return;
            }
            JsonNode messages = body.path("messages");
            JsonNode last = messages.get(messages.size() - 1);
            if (last.path("content").asText().equals("Wait")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                waiting.add(true);
                try {
                    released.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (last.path("content").asText().equals("Failure")) {
                send(exchange, 503, "{\"error\":{\"message\":\"Unavailable\"}}");
                return;
            }
            send(exchange, 200, body.has("tools") && !last.path("role").asText().equals("tool") ? toolStream() : textStream("Hello", " world"));
        }
    }

    private static void send(HttpExchange exchange, int status, String body)
            throws IOException
    {
        exchange.getResponseHeaders().add("Content-Type", body.startsWith("data:") || body.startsWith(":") ? "text/event-stream" : "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public static String chunk(ObjectNode delta, String finish, boolean usage)
    {
        ObjectNode body = JSON.createObjectNode().put("id", "completion-1").put("object", "chat.completion.chunk").put("model", MODEL);
        ObjectNode choice = body.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finish == null) {
            choice.putNull("finish_reason");
        }
        else {
            choice.put("finish_reason", finish);
        }
        if (usage) {
            body.putObject("usage").put("prompt_tokens", 12).put("completion_tokens", 4).put("prompt_cache_hit_tokens", 3).put("total_tokens", 16);
        }
        return "data: " + body + "\n\n";
    }

    public static String textStream(String... deltas)
    {
        StringBuilder stream = new StringBuilder(chunk(JSON.createObjectNode().put("role", "assistant"), null, false));
        for (String delta : deltas) {
            stream.append(chunk(JSON.createObjectNode().put("content", delta), null, false));
        }
        return stream.append(chunk(JSON.createObjectNode(), "stop", true)).append("data: [DONE]\n\n").toString();
    }

    public static String toolStream()
    {
        StringBuilder stream = new StringBuilder(chunk(JSON.createObjectNode().put("role", "assistant").put("reasoning_content", "private thought"), null, false));
        ObjectNode first = JSON.createObjectNode();
        first.putArray("tool_calls").addObject().put("index", 0).put("id", "call-1").put("type", "function").putObject("function")
                .put("name", "contract_lookup").put("arguments", "{\"key\":");
        stream.append(chunk(first, null, false));
        ObjectNode second = JSON.createObjectNode();
        second.putArray("tool_calls").addObject().put("index", 0).putObject("function").put("arguments", "\"CONTRACT-1\"}");
        return stream.append(chunk(second, null, false)).append(chunk(JSON.createObjectNode(), "tool_calls", true)).append("data: [DONE]\n\n").toString();
    }

    @Override
    public void close()
    {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    public record Request(String method, String path, Map<String, List<String>> headers, JsonNode body)
    {
        public String authorization()
        {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("Authorization"))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
        }
    }

    private record Reply(int status, String body) {}
}

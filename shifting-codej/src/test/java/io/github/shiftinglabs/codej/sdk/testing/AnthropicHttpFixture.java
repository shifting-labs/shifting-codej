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

/** Loopback-only wire fixture shared by the real SDK adapter, connector contracts and public facade tests. */
public final class AnthropicHttpFixture
        implements AutoCloseable
{
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final String MODEL = "claude-contract";
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("anthropic-fixture-", 0).factory());
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final BlockingQueue<Boolean> waiting = new LinkedBlockingQueue<>();

    public AnthropicHttpFixture()
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
            throw new AssertionError("No pending HTTP stream");
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
                String info = "{\"id\":\"" + MODEL + "\",\"type\":\"model\",\"display_name\":\"Contract Claude\",\"created_at\":\"2026-11-13T00:00:00Z\"}";
                send(exchange, 200, exchange.getRequestURI().getPath().endsWith("/models")
                        ? "{\"data\":[" + info + "],\"has_more\":false,\"first_id\":\"" + MODEL + "\",\"last_id\":\"" + MODEL + "\"}" : info);
                return;
            }
            String prompt = body.path("messages").toString();
            if (prompt.contains("Wait")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(start().getBytes(StandardCharsets.UTF_8));
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
            if (prompt.contains("Failure")) {
                send(exchange, 503, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Unavailable\"}}");
                return;
            }
            JsonNode last = body.path("messages").get(body.path("messages").size() - 1);
            boolean toolResult = last.path("content").toString().contains("tool_result");
            send(exchange, 200, body.has("tools") && !toolResult ? toolStream(false) : textStream("Hello", " world"));
        }
    }

    private static void send(HttpExchange exchange, int status, String body)
            throws IOException
    {
        exchange.getResponseHeaders().add("Content-Type", body.startsWith("event:") ? "text/event-stream" : "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public static String event(String type, String fields)
    {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}\n\n";
    }

    public static String start()
    {
        return event("message_start", "\"message\":{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + MODEL
                + "\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}");
    }

    public static String end(String reason)
    {
        return event("message_delta", "\"delta\":{\"stop_reason\":\"" + reason + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":4}")
                + event("message_stop", "");
    }

    public static String textStream(String... deltas)
    {
        StringBuilder body = new StringBuilder(start());
        body.append(event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}"));
        for (String delta : deltas) {
            body.append(event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + JSON.valueToTree(delta) + "}"));
        }
        return body.append(event("content_block_stop", "\"index\":0")).append(end("end_turn")).toString();
    }

    public static String toolStream(boolean thinking)
    {
        StringBuilder body = new StringBuilder(start());
        int index = 0;
        if (thinking) {
            body.append(event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\",\"future_field\":{\"x\":1}}"))
                    .append(event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private thought\"}"))
                    .append(event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"signed-state\"}"))
                    .append(event("content_block_stop", "\"index\":0"));
            index++;
        }
        body.append(event("content_block_start", "\"index\":" + index + ",\"content_block\":{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"contract_lookup\",\"input\":{}}"))
                .append(event("content_block_delta", "\"index\":" + index + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"key\\\":\"}"))
                .append(event("content_block_delta", "\"index\":" + index + ",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"CONTRACT-1\\\"}\"}"))
                .append(event("content_block_stop", "\"index\":" + index));
        return body.append(end("tool_use")).toString();
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

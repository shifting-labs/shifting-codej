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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

/**
 * The loopback HTTP endpoint for the broker's session tokens. A provider reaches it with its session's bearer; a browser
 * page cannot, since it sends an {@code Origin} and cannot pin the {@code Host} to this address.
 */
final class McpLoopbackServer
        implements AutoCloseable
{
    private static final String PATH = "/mcp";
    private static final String BEARER = "Bearer ";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final PermissionBroker broker;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final HttpServer server;
    private final String host;

    static McpLoopbackServer start(PermissionBroker broker, ObjectMapper mapper)
    {
        try {
            return new McpLoopbackServer(broker, mapper);
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Unable to start the agent tool server", failure);
        }
    }

    private McpLoopbackServer(PermissionBroker broker, ObjectMapper mapper)
            throws IOException
    {
        this.broker = requireNonNull(broker, "broker is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.host = "127.0.0.1:" + server.getAddress().getPort();
        server.setExecutor(executor);
        server.createContext(PATH, this::handle);
        server.start();
    }

    URI endpoint()
    {
        return URI.create("http://" + host + PATH);
    }

    private void handle(HttpExchange exchange)
            throws IOException
    {
        try (exchange) {
            Headers headers = exchange.getRequestHeaders();
            String authorization = headers.getFirst("Authorization");
            if (!exchange.getRequestURI().getRawPath().equals(PATH) || exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 404, null);
            }
            // Streamable HTTP clients probe with GET for a server event stream; 405 says there is none.
            else if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, null);
            }
            else if (headers.containsKey("Origin") || !host.equals(headers.getFirst("Host"))) {
                reply(exchange, 403, null);
            }
            else if (authorization == null || !authorization.startsWith(BEARER)) {
                reply(exchange, 401, null);
            }
            else {
                call(exchange, authorization.substring(BEARER.length()));
            }
        }
    }

    private void call(HttpExchange exchange, String token)
            throws IOException
    {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            reply(exchange, 413, null);
            return;
        }
        JsonNode request;
        try {
            request = mapper.readTree(body);
        }
        catch (JsonProcessingException failure) {
            reply(exchange, 400, null);
            return;
        }
        if (request.isMissingNode()) {
            reply(exchange, 400, null);
            return;
        }
        McpHttpResponse response;
        try {
            response = broker.handle(token, request);
        }
        catch (RuntimeException failure) {
            reply(exchange, 500, null);
            return;
        }
        reply(exchange, response.status(), response.body().isPresent() ? mapper.writeValueAsBytes(response.body().orElseThrow()) : null);
    }

    private static void reply(HttpExchange exchange, int status, byte[] body)
            throws IOException
    {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow();
    }
}

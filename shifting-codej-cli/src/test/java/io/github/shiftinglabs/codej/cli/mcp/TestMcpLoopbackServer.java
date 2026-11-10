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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.cli.TaskScopes;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.CodeQueryBridge;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMcpLoopbackServer
{
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path worktree;

    @Test
    void servesOnlyASessionsBearerFromThisAddress()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        try (McpRegistration session = register(broker)) {
            URI endpoint = session.endpoint();
            String host = endpoint.getHost() + ":" + endpoint.getPort();
            Map<String, String> bearer = ImmutableMap.of("Authorization", "Bearer " + session.token());

            String listed = request(endpoint, "POST", endpoint.getRawPath(), host, bearer, LIST);
            assertThat(listed).startsWith("HTTP/1.1 200").contains("\"code_query\"");

            assertThat(request(endpoint, "POST", endpoint.getRawPath(), host, ImmutableMap.of(), LIST)).startsWith("HTTP/1.1 401");
            assertThat(request(endpoint, "POST", endpoint.getRawPath(), host, ImmutableMap.of("Authorization", "Bearer guessed"), LIST)).startsWith("HTTP/1.1 401");
            assertThat(request(endpoint, "POST", endpoint.getRawPath(), host, ImmutableMap.<String, String>builder().putAll(bearer).put("Origin", "https://example.com").build(), LIST))
                    .startsWith("HTTP/1.1 403");
            assertThat(request(endpoint, "POST", endpoint.getRawPath(), "example.com", bearer, LIST)).startsWith("HTTP/1.1 403");
            assertThat(request(endpoint, "GET", endpoint.getRawPath(), host, bearer, "")).startsWith("HTTP/1.1 405");
            assertThat(request(endpoint, "POST", endpoint.getRawPath() + "/other", host, bearer, LIST)).startsWith("HTTP/1.1 404");
            assertThat(request(endpoint, "POST", endpoint.getRawPath(), host, bearer, "{")).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    void stopsWithItsLastSessionAndStartsForTheNext()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(objectMapper);
        McpRegistration first = register(broker);
        McpRegistration second = register(broker);
        URI endpoint = first.endpoint();
        assertThat(second.endpoint()).isEqualTo(endpoint);

        first.close();
        assertThat(list(second)).startsWith("HTTP/1.1 200");

        second.close();
        assertThatThrownBy(() -> list(second)).isInstanceOf(ConnectException.class);

        try (McpRegistration next = register(broker)) {
            assertThat(list(next)).startsWith("HTTP/1.1 200");
        }
    }

    private McpRegistration register(PermissionBroker broker)
    {
        return broker.registerTools("task", worktree, TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false),
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()),
                ImmutableList.of(new CodeQueryBridge(query -> "symbols", "use the graph").tool())).orElseThrow();
    }

    private static String list(McpRegistration session)
            throws IOException
    {
        URI endpoint = session.endpoint();
        return request(endpoint, "POST", endpoint.getRawPath(), endpoint.getHost() + ":" + endpoint.getPort(),
                ImmutableMap.of("Authorization", "Bearer " + session.token()), LIST);
    }

    /** A raw request, since the JDK client will not send a chosen {@code Host}. */
    private static String request(URI endpoint, String method, String path, String host, Map<String, String> headers, String body)
            throws IOException
    {
        try (Socket socket = new Socket(endpoint.getHost(), endpoint.getPort())) {
            byte[] content = body.getBytes(UTF_8);
            StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("Content-Type: application/json\r\nContent-Length: ").append(content.length).append("\r\n\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(head.toString().getBytes(UTF_8));
            output.write(content);
            output.flush();
            return new String(socket.getInputStream().readAllBytes(), UTF_8);
        }
    }
}

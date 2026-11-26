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
package io.github.shiftinglabs.codej.cli.antigravity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Call;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ElicitationParams;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ElicitationRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HostReply;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HostRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Incoming;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Method;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Notification;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.NumberId;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Outgoing;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionParams;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Rejection;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.RpcError;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.RpcId;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SessionId;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SessionNotification;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Success;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.process.ManagedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessStream;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.process.Utf8LineBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.CANCEL_SESSION;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.CLOSE_SESSION;
import static io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.VERSION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

final class AcpClient
{
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new Jdk8Module())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final ProcessSupervisor supervisor;
    private final SecretRedactor redactor;
    private final BiConsumer<AcpClient, SessionNotification> updates;
    private final RequestHandler requests;
    private final ManagedProcess process;
    private final CompletableFuture<ProcessExit> exit;
    private final Object stopLock = new Object();
    private final CompletableFuture<Void> acknowledged = new CompletableFuture<>();
    private final Map<Long, Pending<?>> pending = new HashMap<>();
    private long nextId;
    private AgentUnavailableException disconnected;
    private volatile boolean invalidOutput;
    private boolean stopped;

    AcpClient(ProcessSupervisor supervisor, ProcessSpec spec, SecretRedactor redactor, BiConsumer<AcpClient, SessionNotification> updates, RequestHandler requests)
    {
        this.supervisor = supervisor;
        this.redactor = redactor;
        this.updates = updates;
        this.requests = requests;
        Utf8LineBuffer lines = new Utf8LineBuffer(MAX_FRAME_BYTES, true, this::receive);
        process = supervisor.prepare(spec).start((stream, bytes) -> {
            if (stream == ProcessStream.STDOUT) {
                try {
                    lines.accept(bytes);
                }
                catch (RuntimeException failure) {
                    invalidOutput(failure);
                }
            }
        });
        exit = process.exit().toCompletableFuture().whenComplete((_, failure) -> {
            try {
                lines.finish();
            }
            catch (RuntimeException invalid) {
                invalidOutput(invalid);
            }
            disconnect(new AgentUnavailableException("Antigravity ACP process exited before a reply", failure));
        });
    }

    private void invalidOutput(RuntimeException failure)
    {
        invalidOutput = true;
        disconnect(new AgentUnavailableException("Invalid Antigravity ACP output", failure));
    }

    static String encode(Object value)
    {
        try {
            return JSON.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new AgentUnavailableException("Unable to encode Antigravity ACP data", failure);
        }
    }

    synchronized <P, R> CompletableFuture<R> request(Method<P, R> method, P params)
    {
        if (disconnected != null) {
            throw disconnected;
        }
        long id = ++nextId;
        CompletableFuture<R> reply = new CompletableFuture<>();
        pending.put(id, new Pending<>(method.resultType(), reply));
        send(new Call<>(VERSION, id, method.name(), params));
        return reply;
    }

    CompletableFuture<Void> cancelSession(SessionId session)
    {
        return send(new Notification(VERSION, CANCEL_SESSION, session));
    }

    private CompletableFuture<Void> send(Outgoing frame)
    {
        try {
            byte[] bytes = (encode(frame) + "\n").getBytes(UTF_8);
            return process.write(ByteBuffer.wrap(bytes)).toCompletableFuture().whenComplete((_, failure) -> {
                if (failure != null) {
                    disconnect(new AgentUnavailableException("Unable to write to Antigravity ACP", failure));
                }
            });
        }
        catch (RuntimeException failure) {
            disconnect(new AgentUnavailableException("Unable to write to Antigravity ACP", failure));
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void receive(String line)
    {
        Incoming frame;
        try {
            frame = JSON.readValue(line, Incoming.class);
        }
        catch (JsonProcessingException invalid) {
            throw new AgentUnavailableException("Invalid ACP JSON");
        }
        if (frame == null || !VERSION.equals(frame.jsonrpc())) {
            throw new AgentUnavailableException("Invalid ACP envelope");
        }
        if (frame.method().isPresent()) {
            receiveMethod(frame.method().orElseThrow(), frame);
            return;
        }
        if (frame.id().isEmpty() || !(frame.id().orElseThrow() instanceof NumberId id)) {
            throw new AgentUnavailableException("Invalid ACP reply ID");
        }
        Pending<?> reply;
        synchronized (this) {
            reply = pending.remove(id.value());
        }
        if (reply == null) {
            return;
        }
        if (frame.error().isPresent()) {
            RpcError error = frame.error().orElseThrow();
            reply.future().completeExceptionally(new RpcException(error.code(), redactor.redact(error.message().orElse("Request failed"))));
        }
        else {
            reply.complete(frame.result());
        }
    }

    private void receiveMethod(String method, Incoming frame)
    {
        if (frame.id().isEmpty()) {
            if (method.equals("session/update")) {
                SessionNotification update;
                try {
                    update = readObject(frame.params(), SessionNotification.class);
                }
                catch (IOException ignored) {
                    // Ignore malformed update payloads without losing an otherwise valid turn.
                    return;
                }
                updates.accept(this, update);
            }
            return;
        }

        RpcId id = frame.id().orElseThrow();
        Optional<HostRequest> request;
        try {
            request = switch (method) {
                case "session/request_permission" -> Optional.of(new PermissionRequest(readObject(frame.params(), PermissionParams.class)));
                case "elicitation/create" -> Optional.of(new ElicitationRequest(readObject(frame.params(), ElicitationParams.class)));
                default -> Optional.empty();
            };
        }
        catch (IOException invalid) {
            reject(id, -32602, "Invalid ACP interaction");
            return;
        }
        if (request.isEmpty()) {
            reject(id, -32601, "Method not supported");
            return;
        }
        var result = requests.handle(this, request.orElseThrow());
        if (!result.isDone()) {
            // Keep reading updates and cancellation while the host waits for a human decision.
            result.thenCompose(value -> send(new Success(VERSION, id, value)).orTimeout(5, TimeUnit.SECONDS))
                    .exceptionally(failure -> {
                        disconnect(new AgentUnavailableException("Unable to answer Antigravity ACP", failure));
                        return null;
                    });
            return;
        }
        // Bound pending writes even if a malformed peer sends requests without reading its replies.
        await(send(new Success(VERSION, id, result.join())), Duration.ofSeconds(5));
    }

    private void reject(RpcId id, int code, String message)
    {
        await(send(new Rejection(VERSION, id, new RpcError(code, Optional.of(message)))), Duration.ofSeconds(5));
    }

    private static <T> T readObject(Optional<TokenBuffer> payload, Class<T> type)
            throws IOException
    {
        if (payload.isEmpty() || payload.orElseThrow().firstToken() != JsonToken.START_OBJECT) {
            throw new IOException("Expected an ACP payload object");
        }
        try (JsonParser parser = payload.orElseThrow().asParser()) {
            return JSON.readValue(parser, type);
        }
    }

    private record Pending<R>(Class<R> type, CompletableFuture<R> future)
    {
        Pending
        {
            requireNonNull(type, "type is null");
            requireNonNull(future, "future is null");
        }

        void complete(Optional<TokenBuffer> payload)
        {
            try {
                future.complete(readObject(payload, type));
            }
            catch (IOException failure) {
                future.completeExceptionally(new AgentUnavailableException("Invalid ACP result"));
            }
        }
    }

    @FunctionalInterface
    interface RequestHandler
    {
        CompletableFuture<HostReply> handle(AcpClient sender, HostRequest request);
    }

    private void disconnect(AgentUnavailableException failure)
    {
        List<Pending<?>> replies;
        synchronized (this) {
            if (disconnected != null) {
                return;
            }
            disconnected = failure;
            replies = ImmutableList.copyOf(pending.values());
            pending.clear();
        }
        replies.forEach(reply -> reply.future().completeExceptionally(failure));
    }

    static <T> T await(CompletableFuture<T> future, Duration timeout)
    {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted waiting for Antigravity ACP", e);
        }
        catch (TimeoutException e) {
            throw new AgentUnavailableException("Timed out waiting for Antigravity ACP", e);
        }
        catch (ExecutionException e) {
            if (e.getCause() instanceof AgentUnavailableException failure) {
                throw failure;
            }
            throw new AgentUnavailableException("Antigravity ACP request failed", e.getCause());
        }
    }

    void endSession(String sessionId, boolean canClose)
    {
        Duration grace = Duration.ofSeconds(3);
        if (canClose) {
            await(request(CLOSE_SESSION, new SessionId(sessionId)), grace);
        }
        process.closeInput();
        ProcessExit result = await(exit, grace);
        if (invalidOutput || result.exitCode() != 0 || result.stdoutTruncated()) {
            throw new AgentUnavailableException("Antigravity ACP did not close the session cleanly");
        }
    }

    // The exit callback uses the protocol lock, so never hold that lock while awaiting process cleanup.
    void stop()
    {
        synchronized (stopLock) {
            if (stopped) {
                return;
            }
            disconnect(new AgentUnavailableException("Antigravity ACP session stopped"));
            if (await(process.stopAndVerify().toCompletableFuture(), STOP_TIMEOUT) != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
                throw new AgentUnavailableException("Antigravity ACP process tree could not be verified stopped");
            }
            stopped = true;
            // The supervisor may still be draining stdout and writing its terminal receipt after a verified stop.
            exit.whenComplete((_, _) -> {
                try {
                    supervisor.acknowledge(process.launchId());
                }
                catch (RuntimeException failure) {
                    System.getLogger(AcpClient.class.getName()).log(System.Logger.Level.WARNING, "Unable to acknowledge Antigravity launch " + process.launchId(), failure);
                }
                acknowledged.complete(null);
            });
        }
    }

    void awaitDrained()
    {
        await(acknowledged, STOP_TIMEOUT);
    }

    static final class RpcException
            extends AgentUnavailableException
    {
        private final int code;

        private RpcException(int code, String detail)
        {
            super(code == -32000 ? "Antigravity authentication required for this profile" : "Antigravity ACP request failed (" + code + "): " + detail);
            this.code = code;
        }

        boolean authenticationRequired()
        {
            return code == -32000;
        }
    }
}

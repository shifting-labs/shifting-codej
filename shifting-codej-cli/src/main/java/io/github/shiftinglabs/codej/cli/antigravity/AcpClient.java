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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.process.ManagedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import io.github.shiftinglabs.codej.cli.process.ProcessStream;
import io.github.shiftinglabs.codej.cli.process.ProcessSupervisor;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.process.Utf8LineBuffer;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static java.nio.charset.StandardCharsets.UTF_8;

final class AcpClient
{
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final ProcessSupervisor supervisor;
    private final SecretRedactor redactor;
    private final BiConsumer<AcpClient, JsonNode> updates;
    private final ManagedProcess process;
    private final CompletableFuture<ProcessExit> exit;
    private final Object stopLock = new Object();
    private final CompletableFuture<Void> acknowledged = new CompletableFuture<>();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new HashMap<>();
    private long nextId;
    private AgentUnavailableException disconnected;
    private volatile boolean invalidOutput;
    private boolean stopped;

    AcpClient(ProcessSupervisor supervisor, ProcessSpec spec, BiConsumer<AcpClient, JsonNode> updates)
    {
        this.supervisor = supervisor;
        this.redactor = SecretRedactor.fromEnvironment(spec.environment());
        this.updates = updates;
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

    static ObjectNode object()
    {
        return JSON.createObjectNode();
    }

    synchronized CompletableFuture<JsonNode> request(String method, ObjectNode params)
    {
        if (disconnected != null) {
            throw disconnected;
        }
        long id = ++nextId;
        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        pending.put(id, reply);
        send(object().put("jsonrpc", "2.0").put("id", id).put("method", method).set("params", params));
        return reply;
    }

    CompletableFuture<Void> notify(String method, ObjectNode params)
    {
        return send(object().put("jsonrpc", "2.0").put("method", method).set("params", params));
    }

    private CompletableFuture<Void> send(ObjectNode frame)
    {
        try {
            byte[] bytes = (frame + "\n").getBytes(UTF_8);
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
        JsonNode frame;
        try {
            frame = JSON.readTree(line);
        }
        catch (JsonProcessingException invalid) {
            throw new AgentUnavailableException("Invalid ACP JSON");
        }
        if (frame == null || !frame.isObject() || !"2.0".equals(frame.path("jsonrpc").textValue())) {
            throw new AgentUnavailableException("Invalid ACP envelope");
        }
        if (frame.has("method")) {
            receiveMethod(frame);
            return;
        }
        CompletableFuture<JsonNode> reply;
        synchronized (this) {
            if (!frame.path("id").isIntegralNumber() || !frame.get("id").canConvertToLong()) {
                throw new AgentUnavailableException("Invalid ACP reply ID");
            }
            reply = pending.remove(frame.get("id").longValue());
        }
        if (reply == null) {
            return;
        }
        if (frame.has("error")) {
            JsonNode error = frame.get("error");
            reply.completeExceptionally(new RpcException(error.path("code").asInt(), redactor.redact(error.path("message").asText("Request failed"))));
        }
        else if (frame.path("result").isObject()) {
            reply.complete(frame.get("result"));
        }
        else {
            reply.completeExceptionally(new AgentUnavailableException("Invalid ACP result"));
        }
    }

    private void receiveMethod(JsonNode frame)
    {
        if (!frame.has("id")) {
            if ("session/update".equals(frame.path("method").textValue())) {
                updates.accept(this, frame.path("params"));
            }
            return;
        }

        ObjectNode response = object().put("jsonrpc", "2.0").set("id", frame.get("id"));
        if ("session/request_permission".equals(frame.path("method").textValue())) {
            // The host only grants this connector read-only access. Never widen it through a provider prompt.
            ObjectNode outcome = response.putObject("result").putObject("outcome").put("outcome", "cancelled");
            for (JsonNode option : frame.path("params").path("options")) {
                if ("reject_once".equals(option.path("kind").textValue()) && option.path("optionId").isTextual()) {
                    outcome.put("outcome", "selected").put("optionId", option.get("optionId").textValue());
                    break;
                }
            }
        }
        else {
            response.putObject("error").put("code", -32601).put("message", "Method not supported");
        }
        // Bound pending writes even if a malformed peer sends requests without reading its replies.
        await(send(response), Duration.ofSeconds(5));
    }

    private void disconnect(AgentUnavailableException failure)
    {
        List<CompletableFuture<JsonNode>> replies;
        synchronized (this) {
            if (disconnected != null) {
                return;
            }
            disconnected = failure;
            replies = List.copyOf(pending.values());
            pending.clear();
        }
        replies.forEach(reply -> reply.completeExceptionally(failure));
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
            await(request("session/close", object().put("sessionId", sessionId)), grace);
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

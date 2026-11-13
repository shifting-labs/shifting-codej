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
package io.github.shiftinglabs.codej.sdk.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Direct DeepSeek Chat Completions; all agent/session/tool orchestration remains in the shared engine. */
public final class DeepSeekModelClient
        implements ModelClient
{
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final List<String> EFFORTS = List.of("none", "low", "high", "max");
    private final DeepSeekApiConfig config;
    private final ApiHttpTransport transport;
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("codej-deepseek-stream-", 0).factory());
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SecretRedactor> redactor = new AtomicReference<>(SecretRedactor.fromEnvironment(Map.of()));
    private volatile boolean closed;

    public DeepSeekModelClient(DeepSeekApiConfig config)
    {
        this.config = requireNonNull(config, "config is null");
        transport = new ApiHttpTransport(config.timeout);
    }

    public SecretRedactor redactor() { return redactor.get(); }

    @Override
    public ModelCapabilities capabilities(String model)
    {
        checkText(model, "model");
        if ((!model.equals(config.model) && !model.startsWith("deepseek-")) || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
            throw invalid("Unsupported DeepSeek model identifier");
        }
        // The first direct lane intentionally exposes text and client tools only, even when a model supports images.
        return new ModelCapabilities(true, Set.of(), Set.copyOf(EFFORTS), Set.of());
    }

    @Override
    public AgentProviderStatus probe(Optional<String> model)
    {
        requireNonNull(model, "model is null");
        try {
            String selected = model.orElse(config.model);
            capabilities(selected);
            if (models().stream().noneMatch(value -> value.id().equals(selected))) {
                return status(AgentProviderStatus.Readiness.UNSUPPORTED, "Selected DeepSeek model is absent from the catalog");
            }
            return status(AgentProviderStatus.Readiness.READY, "DeepSeek API is ready");
        }
        catch (RuntimeException failure) {
            ModelClientException error = normalize(failure);
            return status(switch (error.kind()) {
                case AUTHENTICATION_REQUIRED -> AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED;
                case INVALID_REQUEST -> AgentProviderStatus.Readiness.UNSUPPORTED;
                default -> AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE;
            }, error.getMessage());
        }
    }

    private AgentProviderStatus status(AgentProviderStatus.Readiness readiness, String detail)
    {
        return new AgentProviderStatus(new AgentLaneId("deepseek-api"), readiness, Optional.empty(), detail, AgentCapabilities.none());
    }

    @Override
    public List<ProviderModel> models()
    {
        requireOpen();
        try (var response = transport.execute(http("models", config.timeout).GET().build(), config.timeout)) {
            checkStatus(response.statusCode());
            byte[] bytes = response.body().readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) {
                throw protocol();
            }
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.path("data").isArray() || root.path("data").size() > 2048) {
                throw protocol();
            }
            List<ProviderModel> models = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode item : root.path("data")) {
                if (!item.path("id").isTextual() || item.path("id").asText().isBlank() || !ids.add(item.path("id").asText())) {
                    throw protocol();
                }
                String id = item.path("id").textValue();
                if ((!id.equals(config.model) && !id.startsWith("deepseek-")) || !id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")
                        || !redactor().redact(id).equals(id)) {
                    throw protocol();
                }
                String label = redactor().redact(item.hasNonNull("name") ? item.path("name").asText() : id);
                models.add(new ProviderModel(id, label, "DeepSeek API", EFFORTS, Optional.of("high"), id.equals(config.model), List.of("text"), List.of()));
            }
            return List.copyOf(models);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw connection();
        }
        catch (JsonProcessingException failure) {
            throw protocol();
        }
        catch (IOException failure) {
            throw connection();
        }
        catch (RuntimeException failure) {
            throw normalize(failure);
        }
    }

    @Override
    public ModelCall generate(ModelRequest request, ModelEventSink events)
    {
        requireNonNull(request, "request is null");
        requireNonNull(events, "events is null");
        requireOpen();
        capabilities(request.model());
        if (request.serviceTier().isPresent() || request.effort().filter(value -> !EFFORTS.contains(value)).isPresent()
                || request.deadline().filter(value -> !value.isAfter(Instant.now())).isPresent()
                || request.input() instanceof ModelInput.UserMessage user && !user.attachments().isEmpty()) {
            throw invalid("Unsupported DeepSeek options, attachments or expired deadline");
        }
        ArrayNode history = history(request);
        pending(history, request.input());
        Call call = new Call(request, events, history);
        calls.add(call);
        try {
            workers.execute(call::run);
        }
        catch (RuntimeException failure) {
            calls.remove(call);
            throw invalid("DeepSeek API client is closed or at capacity");
        }
        return call;
    }

    private ArrayNode history(ModelRequest request)
    {
        if (request.replayState().isEmpty()) {
            return JSON.createArrayNode();
        }
        var replay = request.replayState().orElseThrow();
        if (!replay.providerId().equals("deepseek") || replay.formatVersion() != 1 || replay.payload().length() > 8 * 1024 * 1024) {
            throw invalid("Foreign, incompatible or oversized DeepSeek replay");
        }
        try {
            JsonNode root = JSON.readTree(replay.payload());
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("messages").isArray()) {
                throw invalid("Invalid DeepSeek replay");
            }
            ArrayNode history = (ArrayNode) root.path("messages");
            for (JsonNode message : history) {
                String role = message.path("role").asText();
                if (!Set.of("user", "assistant", "tool").contains(role) || !message.isObject()
                        || !message.path("content").isTextual() && !(role.equals("assistant") && message.path("content").isNull())
                        || message.hasNonNull("reasoning_content") && !message.path("reasoning_content").isTextual()) {
                    throw invalid("Invalid DeepSeek replay message");
                }
                if (message.has("tool_calls")) {
                    if (!role.equals("assistant") || !message.path("tool_calls").isArray()) {
                        throw invalid("Invalid DeepSeek replay tool batch");
                    }
                    for (JsonNode tool : message.path("tool_calls")) {
                        if (!tool.path("type").asText().equals("function") || !tool.path("id").isTextual() || !tool.path("function").path("name").isTextual()
                                || !tool.path("function").path("arguments").isTextual()) {
                            throw invalid("Invalid DeepSeek replay tool");
                        }
                    }
                }
            }
            return history.deepCopy();
        }
        catch (IOException failure) {
            throw invalid("Invalid DeepSeek replay JSON");
        }
    }

    private static void pending(ArrayNode history, ModelInput input)
    {
        List<String> ids = new ArrayList<>();
        if (!history.isEmpty()) {
            history.get(history.size() - 1).path("tool_calls").forEach(tool -> ids.add(tool.path("id").asText()));
        }
        if (input instanceof ModelInput.ToolResults results) {
            if (ids.isEmpty() || !ids.equals(results.results().stream().map(ModelInput.ToolResult::callId).toList())) {
                throw invalid("Tool results do not answer the pending DeepSeek tool batch");
            }
        }
        else if (!ids.isEmpty()) {
            throw invalid("Pending DeepSeek tool results must precede new user input");
        }
    }

    private HttpRequest.Builder http(String path, Duration timeout)
    {
        String value;
        try {
            value = config.key.get();
        }
        catch (RuntimeException failure) {
            throw credential();
        }
        if (value == null || value.isBlank() || value.length() < 4 || value.length() > 8192 || value.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw credential();
        }
        String secret = value;
        redactor.updateAndGet(previous -> previous.withSecret(secret));
        URI endpoint = URI.create(config.baseUrl.toString().replaceAll("/+$", "") + "/" + path);
        return HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", "Bearer " + value).header("Content-Type", "application/json");
    }

    private void requireOpen()
    {
        if (closed) {
            throw invalid("DeepSeek API client is closed");
        }
    }

    static ModelClientException invalid(String message) { return new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, message, false); }

    static ModelClientException protocol() { return new ModelClientException(ModelClientException.Kind.PROTOCOL, "Invalid or incomplete DeepSeek response", true); }

    private static ModelClientException connection()
    {
        return new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "DeepSeek API connection failed", true);
    }

    private static ModelClientException credential()
    {
        return new ModelClientException(ModelClientException.Kind.AUTHENTICATION_REQUIRED, "DeepSeek API key is missing or invalid", false);
    }

    private static ModelClientException normalize(RuntimeException failure)
    {
        return failure instanceof ModelClientException normalized ? normalized : protocol();
    }

    private static void checkStatus(int status)
    {
        if (status == 200) {
            return;
        }
        var kind = switch (status) {
            case 401, 403 -> ModelClientException.Kind.AUTHENTICATION_REQUIRED;
            case 402, 429 -> ModelClientException.Kind.RATE_LIMITED;
            case 408, 409 -> ModelClientException.Kind.TEMPORARILY_UNAVAILABLE;
            default -> status >= 500 ? ModelClientException.Kind.TEMPORARILY_UNAVAILABLE : ModelClientException.Kind.INVALID_REQUEST;
        };
        throw new ModelClientException(kind, "DeepSeek API request failed (HTTP " + status + ")", status >= 500 || status == 408 || status == 409);
    }

    @Override
    public void close()
    {
        closed = true;
        calls.forEach(Call::cancel);
        workers.shutdown();
        transport.close();
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "DeepSeek API worker shutdown is unconfirmed", true);
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw connection();
        }
    }

    private final class Call
            implements ModelCall
    {
        private final ModelRequest request;
        private final ModelEventSink events;
        private final ArrayNode history;
        private final CompletableFuture<ModelResponse> result = new CompletableFuture<>();
        private volatile boolean cancelled;
        private volatile Thread runner;
        private volatile ApiHttpTransport.Response stream;

        Call(ModelRequest request, ModelEventSink events, ArrayNode history)
        {
            this.request = request;
            this.events = events;
            this.history = history;
        }

        @Override
        public CompletionStage<ModelResponse> completion() { return result.minimalCompletionStage(); }

        @Override
        public void cancel()
        {
            if (result.isDone()) {
                return;
            }
            cancelled = true;
            Thread active = runner;
            if (active != null) {
                active.interrupt();
            }
            var response = stream;
            if (response != null) {
                try {
                    response.close();
                }
                catch (RuntimeException ignored) {
                    // Completion and owned transport shutdown remain the quiescence fences.
                }
            }
        }

        void run()
        {
            runner = Thread.currentThread();
            ModelResponse response = null;
            RuntimeException error = null;
            try {
                if (cancelled) {
                    throw new CancellationException();
                }
                if (request.input() instanceof ModelInput.UserMessage user) {
                    history.addObject().put("role", "user").put("content", user.text());
                }
                else if (request.input() instanceof ModelInput.ToolResults tools) {
                    tools.results().forEach(tool -> history.addObject().put("role", "tool").put("tool_call_id", tool.callId())
                            .put("content", tool.error() ? "Host tool error: " + tool.text() : tool.text()));
                }
                ObjectNode body = JSON.createObjectNode().put("model", request.model()).put("max_tokens", request.maximumOutputTokens()).put("stream", true);
                ArrayNode messages = body.putArray("messages");
                if (!request.instructions().isEmpty()) {
                    messages.addObject().put("role", "system").put("content", request.instructions());
                }
                messages.addAll(history);
                body.putObject("stream_options").put("include_usage", true);
                request.effort().ifPresent(value -> body.put("reasoning_effort", value));
                if (!request.tools().isEmpty()) {
                    ArrayNode tools = body.putArray("tools");
                    for (var tool : request.tools()) {
                        tools.addObject().put("type", "function").putObject("function").put("name", tool.name()).put("description", tool.description())
                                .set("parameters", JSON.readTree(tool.inputSchema()));
                    }
                }
                byte[] bytes = JSON.writeValueAsBytes(body);
                if (bytes.length > 16 * 1024 * 1024) {
                    throw invalid("DeepSeek request exceeds its body limit");
                }
                Duration timeout = config.timeout;
                if (request.deadline().isPresent()) {
                    Duration remaining = Duration.between(Instant.now(), request.deadline().orElseThrow());
                    if (remaining.isNegative() || remaining.isZero()) {
                        throw invalid("DeepSeek request deadline expired");
                    }
                    if (remaining.compareTo(timeout) < 0) {
                        timeout = remaining;
                    }
                }
                var http = http("chat/completions", timeout).header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                if (cancelled) {
                    throw new CancellationException();
                }
                try (var reply = transport.execute(http, timeout)) {
                    stream = reply;
                    checkStatus(reply.statusCode());
                    if (cancelled) {
                        throw new CancellationException();
                    }
                    String type = reply.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("content-type"))
                            .flatMap(entry -> entry.getValue().stream()).findFirst().orElse("");
                    if (!type.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                        throw protocol();
                    }
                    response = new DeepSeekStream(events, DeepSeekModelClient.this::redactor, () -> cancelled).read(reply.body(), history);
                    if (!redactor().redact(response.replayState().payload()).equals(response.replayState().payload())) {
                        throw protocol();
                    }
                }
            }
            catch (IOException | InterruptedException failure) {
                error = cancelled ? new CancellationException() : connection();
            }
            catch (RuntimeException failure) {
                error = cancelled ? new CancellationException() : normalize(failure);
            }
            catch (Throwable failure) {
                error = protocol();
            }
            finally {
                stream = null;
                Thread.interrupted();
                calls.remove(this);
            }
            if (error == null) {
                result.complete(response);
            }
            else {
                result.completeExceptionally(error);
            }
        }
    }
}

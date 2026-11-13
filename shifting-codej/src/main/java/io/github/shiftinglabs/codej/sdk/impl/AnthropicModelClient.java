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

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.LogLevel;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.models.ModelListParams;
import com.anthropic.models.models.ModelRetrieveParams;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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

/** Anthropic Messages adapter. SDK HTTP/stream/error handling stays here; tools are executed only by the shared engine. */
public final class AnthropicModelClient
        implements ModelClient
{
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    // Generated SDK models carry explicit Jackson creators; avoid Kotlin reflection, whose metadata cannot be relocated by Maven Shade.
    private static final JsonMapper WIRE = AnthropicJson.mapper();
    private static final int MAX_REPLAY = 8 * 1024 * 1024;
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private final ClaudeApiConfig config;
    private final AnthropicClient sdk;
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("codej-anthropic-stream-", 0).factory());
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final AtomicReference<SecretRedactor> redactor = new AtomicReference<>(SecretRedactor.fromEnvironment(Map.of()));
    private volatile boolean closed;

    public AnthropicModelClient(ClaudeApiConfig config)
    {
        this.config = requireNonNull(config, "config is null");
        sdk = new AnthropicClientImpl(ClientOptions.builder().httpClient(new AnthropicHttpTransport(config.timeout))
                .baseUrl(config.baseUrl.toString())
                .jsonMapper(WIRE).maxRetries(0).timeout(config.timeout).streamHandlerExecutor(Runnable::run).openTelemetry(false).logLevel(LogLevel.OFF).build());
    }

    public SecretRedactor redactor() { return redactor.get(); }

    @Override
    public ModelCapabilities capabilities(String model)
    {
        checkText(model, "model");
        if ((!model.equals(config.model) && !model.startsWith("claude-")) || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
            throw invalid("Unsupported Claude model identifier");
        }
        // Effort and service tiers are deliberately not advertised without a model-specific capability contract.
        return new ModelCapabilities(true, IMAGES, Set.of(), Set.of());
    }

    @Override
    public AgentProviderStatus probe(Optional<String> model)
    {
        requireNonNull(model, "model is null");
        try {
            requireOpen();
            String selected = model.orElse(config.model);
            capabilities(selected);
            sdk.models().retrieve(ModelRetrieveParams.builder().modelId(selected).putAdditionalHeader("Authorization", "Bearer " + key()).build());
            return status(AgentProviderStatus.Readiness.READY, "Claude API is ready");
        }
        catch (RuntimeException failure) {
            ModelClientException normalized = normalize(failure);
            AgentProviderStatus.Readiness readiness = switch (normalized.kind()) {
                case AUTHENTICATION_REQUIRED -> AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED;
                case INVALID_REQUEST -> AgentProviderStatus.Readiness.UNSUPPORTED;
                default -> AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE;
            };
            return status(readiness, normalized.getMessage());
        }
    }

    private AgentProviderStatus status(AgentProviderStatus.Readiness readiness, String detail)
    {
        return new AgentProviderStatus(new AgentLaneId("claude-api"), readiness, Optional.empty(), detail,
                new AgentCapabilities(false, false, false, Set.of(io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy.AUTO), IMAGES));
    }

    @Override
    public List<ProviderModel> models()
    {
        requireOpen();
        List<ProviderModel> models = new ArrayList<>();
        String after = null;
        try {
            for (int page = 0; page < 10; page++) {
                var params = ModelListParams.builder().limit(100).putAdditionalHeader("Authorization", "Bearer " + key());
                if (after != null) {
                    params.afterId(after);
                }
                var result = sdk.models().list(params.build());
                for (var model : result.data()) {
                    models.add(new ProviderModel(model.id(), model.displayName(), "Claude API", List.of(), Optional.empty(), model.id().equals(config.model),
                            List.of("text", "image"), List.of()));
                }
                if (!result.hasNextPage()) {
                    return List.copyOf(models);
                }
                if (result.data().isEmpty() || result.data().getLast().id().equals(after)) {
                    throw invalid("Invalid model catalog pagination");
                }
                after = result.data().getLast().id();
            }
            throw invalid("Model catalog exceeds the page limit");
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
        if (request.effort().isPresent() || request.serviceTier().isPresent()
                || request.deadline().filter(value -> !value.isAfter(Instant.now())).isPresent()) {
            throw invalid("Unsupported options or expired request deadline");
        }
        ArrayNode history = history(request);
        validatePending(history, request.input());
        Call call = new Call(request, events, history);
        calls.add(call);
        try {
            workers.execute(call::run);
        }
        catch (RuntimeException failure) {
            calls.remove(call);
            throw invalid("API client is closed or at capacity");
        }
        return call;
    }

    private ArrayNode history(ModelRequest request)
    {
        if (request.replayState().isEmpty()) {
            return JSON.createArrayNode();
        }
        ModelReplayState state = request.replayState().orElseThrow();
        if (!state.providerId().equals("anthropic") || state.formatVersion() != 1 || state.payload().length() > MAX_REPLAY) {
            throw invalid("Foreign, incompatible or oversized Claude replay");
        }
        try {
            JsonNode root = JSON.readTree(state.payload());
            if (root == null || !root.isObject() || !root.path("messages").isArray()) {
                throw invalid("Invalid Claude replay");
            }
            ArrayNode messages = (ArrayNode) root.path("messages");
            for (JsonNode message : messages) {
                if (!Set.of("user", "assistant").contains(message.path("role").asText()) || !message.path("content").isArray()) {
                    throw invalid("Invalid Claude replay message");
                }
            }
            return messages.deepCopy();
        }
        catch (IOException failure) {
            throw invalid("Invalid Claude replay JSON");
        }
    }

    private static void validatePending(ArrayNode history, ModelInput input)
    {
        List<String> pending = new ArrayList<>();
        if (!history.isEmpty() && history.get(history.size() - 1).path("role").asText().equals("assistant")) {
            history.get(history.size() - 1).path("content").forEach(block -> {
                if (block.path("type").asText().equals("tool_use")) {
                    pending.add(block.path("id").asText());
                }
            });
        }
        if (input instanceof ModelInput.ToolResults results) {
            if (pending.isEmpty() || !pending.equals(results.results().stream().map(ModelInput.ToolResult::callId).toList())) {
                throw invalid("Tool results do not answer the pending Claude tool batch");
            }
        }
        else if (!pending.isEmpty()) {
            throw invalid("Pending Claude tool results must be supplied before new user input");
        }
    }

    private ObjectNode input(ModelInput input)
            throws IOException
    {
        ObjectNode message = JSON.createObjectNode().put("role", "user");
        ArrayNode blocks = message.putArray("content");
        if (input instanceof ModelInput.UserMessage user) {
            if (!user.text().isEmpty()) {
                blocks.addObject().put("type", "text").put("text", user.text());
            }
            long size = 0;
            for (var attachment : user.attachments()) {
                if (!IMAGES.contains(attachment.mediaType()) || !Files.isRegularFile(attachment.path(), LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid("Unsupported Claude attachment");
                }
                byte[] bytes;
                try (var file = Files.newInputStream(attachment.path(), LinkOption.NOFOLLOW_LINKS)) {
                    bytes = file.readNBytes(MAX_REPLAY + 1);
                }
                size += bytes.length;
                if (size > MAX_REPLAY) {
                    throw invalid("Claude attachment budget exceeded");
                }
                blocks.addObject().put("type", "image").putObject("source").put("type", "base64")
                        .put("media_type", attachment.mediaType()).put("data", Base64.getEncoder().encodeToString(bytes));
            }
        }
        else if (input instanceof ModelInput.ToolResults batch) {
            batch.results().forEach(result -> blocks.addObject().put("type", "tool_result").put("tool_use_id", result.callId())
                    .put("content", result.text()).put("is_error", result.error()));
        }
        return message;
    }

    private String key()
    {
        String key;
        try {
            key = config.key.get();
        }
        catch (RuntimeException failure) {
            throw new ModelClientException(ModelClientException.Kind.AUTHENTICATION_REQUIRED, "Claude API credential supplier failed", false);
        }
        if (key == null || key.length() < 4 || key.length() > 8192 || key.isBlank() || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            throw new ModelClientException(ModelClientException.Kind.AUTHENTICATION_REQUIRED, "Claude API key is missing or invalid", false);
        }
        String value = key;
        redactor.updateAndGet(previous -> previous.withSecret(value));
        return key;
    }

    private void requireOpen()
    {
        if (closed) {
            throw invalid("Claude API client is closed");
        }
    }

    static ModelClientException invalid(String detail) { return new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, detail, false); }

    static ModelClientException protocol() { return new ModelClientException(ModelClientException.Kind.PROTOCOL, "Invalid or incomplete Claude response", true); }

    private ModelClientException normalize(RuntimeException failure)
    {
        if (failure instanceof ModelClientException normalized) {
            return normalized;
        }
        if (failure instanceof AnthropicServiceException service) {
            int status = service.statusCode();
            ModelClientException.Kind kind = switch (status) {
                case 401, 403 -> ModelClientException.Kind.AUTHENTICATION_REQUIRED;
                case 429 -> ModelClientException.Kind.RATE_LIMITED;
                case 408, 409, 500, 502, 503, 504, 529 -> ModelClientException.Kind.TEMPORARILY_UNAVAILABLE;
                default -> ModelClientException.Kind.INVALID_REQUEST;
            };
            return new ModelClientException(kind, "Claude API request failed (HTTP " + status + ")", status >= 500 || status == 408 || status == 409);
        }
        if (failure instanceof AnthropicIoException) {
            return new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "Claude API connection failed", true);
        }
        return protocol();
    }

    @Override
    public void close()
    {
        closed = true;
        calls.forEach(Call::cancel);
        workers.shutdown();
        try {
            sdk.close();
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                throw new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "Claude API worker shutdown is unconfirmed", true);
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "Claude API shutdown interrupted", true);
        }
        catch (RuntimeException failure) {
            throw normalize(failure);
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
        private volatile StreamResponse<RawMessageStreamEvent> stream;

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
            StreamResponse<RawMessageStreamEvent> response = stream;
            if (response != null) {
                try {
                    response.close();
                }
                catch (RuntimeException ignored) {
                    // The worker and owned transport retry close; only completion establishes local quiescence.
                }
            }
        }

        private void run()
        {
            runner = Thread.currentThread();
            ModelResponse response = null;
            RuntimeException error = null;
            try {
                if (cancelled) {
                    throw new CancellationException();
                }
                history.add(input(request.input()));
                ObjectNode body = JSON.createObjectNode().put("model", request.model()).put("max_tokens", request.maximumOutputTokens());
                body.set("messages", history);
                body.put("system", request.instructions());
                if (!request.tools().isEmpty()) {
                    ArrayNode tools = body.putArray("tools");
                    for (var tool : request.tools()) {
                        tools.addObject().put("name", tool.name()).put("description", tool.description()).set("input_schema", JSON.readTree(tool.inputSchema()));
                    }
                }
                var params = MessageCreateParams.builder().body(WIRE.convertValue(body, MessageCreateParams.Body.class))
                        .putAdditionalHeader("Authorization", "Bearer " + key()).build();
                Duration timeout = config.timeout;
                if (request.deadline().isPresent()) {
                    Duration remaining = Duration.between(Instant.now(), request.deadline().orElseThrow());
                    if (remaining.isNegative() || remaining.isZero()) {
                        throw invalid("Claude request deadline expired");
                    }
                    if (remaining.compareTo(timeout) < 0) {
                        timeout = remaining;
                    }
                }
                AnthropicStream assembled = new AnthropicStream(events, redactor());
                try (var streaming = sdk.messages().createStreaming(params, RequestOptions.builder().timeout(timeout).build())) {
                    stream = streaming;
                    if (cancelled) {
                        throw new CancellationException();
                    }
                    for (RawMessageStreamEvent event : streaming) {
                        if (cancelled) {
                            throw new CancellationException();
                        }
                        if (assembled.accept(WIRE.valueToTree(event))) {
                            break;
                        }
                    }
                    response = assembled.finish(history);
                    if (!redactor().redact(response.replayState().payload()).equals(response.replayState().payload())) {
                        throw protocol();
                    }
                }
            }
            catch (IOException failure) {
                error = invalid("Claude request input could not be read");
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
            if (error != null) {
                result.completeExceptionally(error);
            }
            else {
                result.complete(response);
            }
        }
    }
}

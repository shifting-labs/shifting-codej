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

import com.fasterxml.jackson.databind.JsonNode;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestModelClient;
import io.github.shiftinglabs.codej.sdk.testing.AnthropicHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.shiftinglabs.codej.sdk.testing.AnthropicHttpFixture.MODEL;
import static io.github.shiftinglabs.codej.sdk.testing.AnthropicHttpFixture.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAnthropicModelClient
        extends AbstractTestModelClient
{
    private AnthropicHttpFixture http;
    private final AtomicReference<String> key = new AtomicReference<>("sk-test-first");

    @BeforeEach
    void setup()
            throws Exception
    {
        http = new AnthropicHttpFixture();
    }

    @AfterEach
    void cleanup() { http.close(); }

    @Override
    protected ModelClient client()
    {
        return new AnthropicModelClient(ClaudeApiConfig.builder(MODEL, key::get).baseUrl(http.baseUrl()).requestTimeout(Duration.ofSeconds(5)).build());
    }

    private static ModelRequest.Builder request(String text)
    {
        return ModelRequest.builder("request-1", MODEL, new ModelInput.UserMessage(text, List.of()), 128);
    }

    @Override
    protected ModelRequest request() { return request("Hello").instructions("Be concise").build(); }

    @Override
    protected List<String> expectedTextDeltas() { return List.of("Hello", " world"); }

    @Override
    protected ModelResponse expectedResponse()
    {
        return new ModelResponse("Hello world", List.of(), ModelResponse.StopReason.COMPLETED,
                new ModelReplayState("anthropic", 1, "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]},"
                        + "{\"role\":\"assistant\",\"content\":[{\"text\":\"Hello world\",\"type\":\"text\"}]}]}"),
                Optional.of(new ProviderUsageSample(Optional.of("msg-1"), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, 12, 0, 4, Optional.empty())));
    }

    @Override
    protected ModelCall pendingCall(ModelClient client)
    {
        ModelCall call = client.generate(request("Wait").build(), text -> {});
        try {
            http.awaitWaiting();
        }
        catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
        return call;
    }

    @Override
    protected ModelCall failedCall(ModelClient client) { return client.generate(request("Failure").build(), text -> {}); }

    @Test
    void encodesTheSdkRequestAndRotatesKeysWithoutAmbientAuthentication()
            throws Exception
    {
        try (ModelClient client = client()) {
            complete(client.generate(request(), text -> {}));
            key.set("sk-test-rotated");
            complete(client.generate(request(), text -> {}));
        }
        assertThat(http.requests()).hasSize(2);
        assertThat(http.requests().getFirst().authorization()).isEqualTo("Bearer sk-test-first");
        assertThat(http.requests().getLast().authorization()).isEqualTo("Bearer sk-test-rotated");
        JsonNode body = http.requests().getFirst().body();
        assertThat(body.path("model").asText()).isEqualTo(MODEL);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(128);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("system").toString()).contains("Be concise");
        assertThat(body.toString()).doesNotContain("sk-test-");
    }

    @Test
    void retainsPrivateSignedBlocksAndSubmitsMatchingToolResultsInOrder()
            throws Exception
    {
        http.reply(200, AnthropicHttpFixture.toolStream(true));
        List<String> visible = new ArrayList<>();
        var tool = new ModelRequest.ToolDefinition("contract_lookup", "Look up a contract", "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}");
        try (ModelClient client = client()) {
            ModelResponse response = complete(client.generate(request("Lookup").tools(List.of(tool)).build(), visible::add));
            assertThat(response.stopReason()).isEqualTo(ModelResponse.StopReason.TOOL_CALLS);
            assertThat(response.toolCalls()).containsExactly(new ModelResponse.ToolCall("call-1", "contract_lookup", "{\"key\":\"CONTRACT-1\"}"));
            assertThat(visible).isEmpty();
            assertThat(response.replayState().payload()).contains("private thought", "signed-state", "future_field");
            var result = new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call-1", "Lookup denied", true)));
            complete(client.generate(ModelRequest.builder("result", MODEL, result, 128).replayState(response.replayState()).tools(List.of(tool)).build(), text -> {}));
            JsonNode body = http.requests().getLast().body();
            assertThat(body.path("tools").get(0).path("input_schema").path("properties").has("key")).isTrue();
            JsonNode replayed = body.path("messages").get(1).path("content").get(0);
            assertThat(replayed.path("signature").asText()).isEqualTo("signed-state");
            assertThat(replayed.path("future_field").path("x").asInt()).isEqualTo(1);
            assertThat(body.path("messages").get(2).path("content").get(0).path("is_error").asBoolean()).isTrue();
            int count = http.requests().size();
            assertThatThrownBy(() -> client.generate(request("New input").replayState(response.replayState()).build(), text -> {}))
                    .isInstanceOf(ModelClientException.class);
            var wrong = new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("wrong", "value", false)));
            assertThatThrownBy(() -> client.generate(ModelRequest.builder("wrong", MODEL, wrong, 128).replayState(response.replayState()).build(), text -> {}))
                    .isInstanceOf(ModelClientException.class);
            assertThat(http.requests()).hasSize(count);
        }
    }

    @Test
    void preservesExplicitNullsAndUnknownBlockFieldsWithoutSynthesizingBeanProperties()
            throws Exception
    {
        http.reply(200, AnthropicHttpFixture.textStream("Answer").replace("\"text\":\"\"}",
                "\"text\":\"\",\"citations\":null,\"future_field\":null}"));
        try (ModelClient client = client()) {
            var response = complete(client.generate(request(), text -> {}));
            JsonNode block = AnthropicHttpFixture.JSON.readTree(response.replayState().payload()).path("messages").get(1).path("content").get(0);
            assertThat(block.has("citations") && block.path("citations").isNull()).isTrue();
            assertThat(block.has("future_field") && block.path("future_field").isNull()).isTrue();
            assertThat(block.has("caller")).isFalse();
            assertThat(block.has("valid")).isFalse();
        }
    }

    @Test
    void rejectsUnrecognizedDeltaFieldsInsteadOfDroppingThem()
            throws Exception
    {
        http.reply(200, AnthropicHttpFixture.textStream("Answer").replace("\"text\":\"Answer\"}", "\"text\":\"Answer\",\"unknown_field\":1}"));
        try (ModelClient client = client()) {
            assertThat(failure(client.generate(request(), text -> {})).kind()).isEqualTo(ModelClientException.Kind.PROTOCOL);
        }
    }

    @Test
    void normalizesHttpErrorsWithoutRetriesOrLeakingResponseSecrets()
            throws Exception
    {
        int[] codes = {401, 429, 503};
        var kinds = List.of(ModelClientException.Kind.AUTHENTICATION_REQUIRED, ModelClientException.Kind.RATE_LIMITED,
                ModelClientException.Kind.TEMPORARILY_UNAVAILABLE);
        try (ModelClient client = client()) {
            for (int i = 0; i < codes.length; i++) {
                http.reply(codes[i], "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"sk-test-first private\"}}");
                ModelClientException failure = failure(client.generate(request(), text -> {}));
                assertThat(failure.kind()).isEqualTo(kinds.get(i));
                assertThat(failure.deliveryUncertain()).isEqualTo(codes[i] >= 500);
                assertThat(failure.getMessage()).doesNotContain("sk-test-first", "private");
                assertThat(failure.getCause()).isNull();
            }
            assertThat(http.requests()).hasSize(3);
        }
    }

    @Test
    void rejectsIncompleteMalformedAndUnsupportedStreams()
            throws Exception
    {
        List<String> streams = List.of(AnthropicHttpFixture.start(), AnthropicHttpFixture.start() + event("message_stop", ""),
                AnthropicHttpFixture.start() + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"server_tool_use\"}"),
                AnthropicHttpFixture.toolStream(false).replace("CONTRACT-1", "CONTRACT-1\\"));
        try (ModelClient client = client()) {
            for (String stream : streams) {
                http.reply(200, stream);
                assertThat(failure(client.generate(request(), text -> {})).deliveryUncertain()).isTrue();
            }
        }
    }

    @Test
    void rejectsUnsupportedOptionsAndForeignReplayBeforeHttp()
    {
        try (ModelClient client = client()) {
            for (ModelRequest request : List.of(request("Hello").effort("high").build(), request("Hello").serviceTier("priority").build(),
                    request("Hello").deadline(Instant.now().minusSeconds(1)).build(),
                    request("Hello").replayState(new ModelReplayState("foreign", 1, "{}")).build(),
                    request("Hello").replayState(new ModelReplayState("anthropic", 1, "null")).build())) {
                assertThatThrownBy(() -> client.generate(request, text -> {})).isInstanceOf(ModelClientException.class);
            }
            assertThat(http.requests()).isEmpty();
        }
    }

    @Test
    void reportsMissingCredentialsWithoutContactingTheEndpoint()
    {
        key.set("");
        try (ModelClient client = client()) {
            assertThat(client.probe(Optional.empty()).readiness().name()).isEqualTo("AUTHENTICATION_REQUIRED");
            assertThat(http.requests()).isEmpty();
        }
    }

    @Test
    void normalizesCacheUsageAndAllSupportedStopReasons()
            throws Exception
    {
        var reasons = java.util.Map.of("end_turn", ModelResponse.StopReason.COMPLETED, "stop_sequence", ModelResponse.StopReason.COMPLETED,
                "max_tokens", ModelResponse.StopReason.OUTPUT_LIMIT, "model_context_window_exceeded", ModelResponse.StopReason.OUTPUT_LIMIT,
                "refusal", ModelResponse.StopReason.REFUSED, "pause_turn", ModelResponse.StopReason.PAUSED);
        try (ModelClient client = client()) {
            for (var reason : reasons.entrySet()) {
                String stream = AnthropicHttpFixture.textStream("Answer").replace("end_turn", reason.getKey())
                        .replace("\"input_tokens\":12", "\"input_tokens\":12,\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2");
                http.reply(200, stream);
                ModelResponse response = complete(client.generate(request(), text -> {}));
                assertThat(response.stopReason()).isEqualTo(reason.getValue());
                assertThat(response.usage().orElseThrow().inputTokens()).isEqualTo(17);
                assertThat(response.usage().orElseThrow().cachedInputTokens()).isEqualTo(3);
                assertThat(response.usage().orElseThrow().outputTokens()).isEqualTo(4);
            }
        }
    }

    @Test
    void encodesImagesWithoutPassingLocalPathsToTheProvider()
            throws Exception
    {
        java.nio.file.Path image = java.nio.file.Files.createTempFile("codej-image-", ".png");
        try (ModelClient client = client()) {
            java.nio.file.Files.write(image, new byte[] {1, 2, 3});
            var input = new ModelInput.UserMessage("Image", List.of(new io.github.shiftinglabs.codej.cli.driver.AgentAttachment("image", "image/png", image)));
            complete(client.generate(ModelRequest.builder("image", MODEL, input, 128).build(), text -> {}));
            JsonNode source = http.requests().getFirst().body().path("messages").get(0).path("content").get(1).path("source");
            assertThat(source.path("type").asText()).isEqualTo("base64");
            assertThat(source.path("media_type").asText()).isEqualTo("image/png");
            assertThat(source.path("data").asText()).isEqualTo("AQID");
            assertThat(http.requests().getFirst().body().toString()).doesNotContain(image.toString());
        }
        finally {
            java.nio.file.Files.deleteIfExists(image);
        }
    }

    @Test
    void boundsAStalledResponseStreamWithTheConfiguredTimeout()
            throws Exception
    {
        try (ModelClient client = new AnthropicModelClient(ClaudeApiConfig.builder(MODEL, key::get).baseUrl(http.baseUrl())
                .requestTimeout(Duration.ofMillis(250)).build())) {
            ModelCall call = client.generate(request("Wait").build(), text -> {});
            http.awaitWaiting();
            assertThat(failure(call).deliveryUncertain()).isTrue();
        }
    }

    @Test
    void refusesRedirectsWithoutForwardingCredentials()
            throws Exception
    {
        http.reply(307, "{}");
        try (ModelClient client = client()) {
            assertThat(failure(client.generate(request(), text -> {}))).isNotNull();
            assertThat(http.requests()).hasSize(1);
        }
    }

    @Test
    void redactsSplitCredentialsAndRefusesToCheckpointThem()
            throws Exception
    {
        http.reply(200, AnthropicHttpFixture.textStream("sk-test-", "first"));
        List<String> visible = new ArrayList<>();
        try (ModelClient client = client()) {
            assertThat(failure(client.generate(request(), visible::add)).kind()).isEqualTo(ModelClientException.Kind.PROTOCOL);
            assertThat(String.join("", visible)).doesNotContain("sk-test-first");
        }
    }

    private static ModelResponse complete(ModelCall call)
            throws Exception
    {
        return call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static ModelClientException failure(ModelCall call)
            throws Exception
    {
        Throwable error = call.completion().handle((result, failure) -> failure).toCompletableFuture().get(3, TimeUnit.SECONDS);
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        assertThat(error).isInstanceOf(ModelClientException.class);
        return (ModelClientException) error;
    }
}

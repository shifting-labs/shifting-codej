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

import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestModelClient;
import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture.JSON;
import static io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture.MODEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekModelClient
        extends AbstractTestModelClient
{
    private DeepSeekHttpFixture http;
    private final AtomicReference<String> key = new AtomicReference<>("sk-test-first");

    @BeforeEach
    void setup()
            throws Exception
    {
        http = new DeepSeekHttpFixture();
    }

    @AfterEach
    void cleanup() { http.close(); }

    @Override
    protected ModelClient client()
    {
        return new DeepSeekModelClient(DeepSeekApiConfig.builder(MODEL, key::get).baseUrl(http.baseUrl()).requestTimeout(Duration.ofSeconds(5)).build());
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
                new ModelReplayState("deepseek", 1, "{\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"Hello world\"}]}"),
                Optional.of(new ProviderUsageSample(Optional.of("completion-1"), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, 12, 3, 4, Optional.empty())));
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
    void sendsExplicitOptionsAndFreshCredentialsThroughTheDirectEndpoint()
            throws Exception
    {
        try (ModelClient client = client()) {
            complete(client.generate(request("Hello").instructions("Be concise").effort("low").build(), text -> {}));
            key.set("sk-test-rotated");
            complete(client.generate(request(), text -> {}));
        }
        var first = http.requests().getFirst();
        assertThat(first.path()).isEqualTo("/chat/completions");
        assertThat(first.authorization()).isEqualTo("Bearer sk-test-first");
        assertThat(http.requests().getLast().authorization()).isEqualTo("Bearer sk-test-rotated");
        assertThat(first.body().path("max_tokens").asInt()).isEqualTo(128);
        assertThat(first.body().path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(first.body().path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(first.body().path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(first.body().toString()).doesNotContain("sk-test-");
        assertThat(http.requests()).hasSize(2);
    }

    @Test
    void replaysReasoningAndToolResultsWithoutExecutingOrDisplayingPrivateContent()
            throws Exception
    {
        http.reply(200, DeepSeekHttpFixture.toolStream());
        List<String> visible = new ArrayList<>();
        var tool = new ModelRequest.ToolDefinition("contract_lookup", "Look up a contract", "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}");
        try (ModelClient client = client()) {
            ModelResponse first = complete(client.generate(request("Lookup").tools(List.of(tool)).build(), visible::add));
            assertThat(first.toolCalls()).containsExactly(new ModelResponse.ToolCall("call-1", "contract_lookup", "{\"key\":\"CONTRACT-1\"}"));
            assertThat(visible).isEmpty();
            assertThat(first.replayState().payload()).contains("private thought");
            var results = new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call-1", "Lookup denied", true)));
            ModelResponse second = complete(client.generate(ModelRequest.builder("results", MODEL, results, 128).replayState(first.replayState())
                    .tools(List.of(tool)).build(), text -> {}));
            var messages = http.requests().getLast().body().path("messages");
            assertThat(messages.get(1).path("reasoning_content").asText()).isEqualTo("private thought");
            assertThat(messages.get(2).path("role").asText()).isEqualTo("tool");
            assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("call-1");
            assertThat(messages.get(2).path("content").asText()).isEqualTo("Host tool error: Lookup denied");
            complete(client.generate(request("Continue").replayState(second.replayState()).build(), text -> {}));
            assertThat(http.requests().getLast().body().toString()).contains("private thought", "Lookup denied", "Hello world");
            int count = http.requests().size();
            assertThatThrownBy(() -> client.generate(request("Wrong").replayState(first.replayState()).build(), text -> {})).isInstanceOf(ModelClientException.class);
            var wrong = new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("wrong", "value", false)));
            assertThatThrownBy(() -> client.generate(ModelRequest.builder("wrong", MODEL, wrong, 128).replayState(first.replayState()).build(), text -> {}))
                    .isInstanceOf(ModelClientException.class);
            assertThat(http.requests()).hasSize(count);
        }
    }

    @Test
    void retainsReasoningFromOrdinaryAssistantTurnsWhenToolsAreAddedLater()
            throws Exception
    {
        String first = DeepSeekHttpFixture.chunk(JSON.createObjectNode().put("reasoning_content", "private text"), null, false)
                + DeepSeekHttpFixture.chunk(JSON.createObjectNode().put("content", "Answer"), "stop", true) + "data: [DONE]\n\n";
        http.reply(200, first);
        try (ModelClient client = client()) {
            ModelResponse response = complete(client.generate(request(), text -> {}));
            var tool = new ModelRequest.ToolDefinition("contract_lookup", "Look up", "{\"type\":\"object\"}");
            complete(client.generate(request("Lookup").replayState(response.replayState()).tools(List.of(tool)).build(), text -> {}));
            assertThat(http.requests().getLast().body().path("messages").get(0).path("content").asText()).isEqualTo("Hello");
            assertThat(http.requests().getLast().body().path("messages").get(1).path("reasoning_content").asText()).isEqualTo("private text");
        }
    }

    @Test
    void assemblesInterleavedClientToolsInTheirDeclaredOrder()
            throws Exception
    {
        var delta = JSON.createObjectNode();
        var tools = delta.putArray("tool_calls");
        tools.addObject().put("index", 0).put("id", "call-a").put("type", "function").putObject("function").put("name", "first").put("arguments", "{");
        tools.addObject().put("index", 1).put("id", "call-b").put("type", "function").putObject("function").put("name", "second").put("arguments", "{");
        var rest = JSON.createObjectNode();
        rest.putArray("tool_calls").addObject().put("index", 1).putObject("function").put("arguments", "}");
        rest.withArray("tool_calls").addObject().put("index", 0).putObject("function").put("arguments", "}");
        http.reply(200, DeepSeekHttpFixture.chunk(delta, null, false) + DeepSeekHttpFixture.chunk(rest, "tool_calls", true) + "data: [DONE]\n\n");
        try (ModelClient client = client()) {
            assertThat(complete(client.generate(request(), text -> {})).toolCalls()).containsExactly(
                    new ModelResponse.ToolCall("call-a", "first", "{}"), new ModelResponse.ToolCall("call-b", "second", "{}"));
        }
    }

    @Test
    void mapsAllFinishReasonsAndCountsFinalUsageOnce()
            throws Exception
    {
        var reasons = java.util.Map.of("stop", ModelResponse.StopReason.COMPLETED, "length", ModelResponse.StopReason.OUTPUT_LIMIT,
                "content_filter", ModelResponse.StopReason.REFUSED, "aborted", ModelResponse.StopReason.PAUSED,
                "insufficient_system_resource", ModelResponse.StopReason.PAUSED);
        try (ModelClient client = client()) {
            for (var reason : reasons.entrySet()) {
                http.reply(200, DeepSeekHttpFixture.textStream("Answer").replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"" + reason.getKey() + "\""));
                ModelResponse response = complete(client.generate(request(), text -> {}));
                assertThat(response.stopReason()).isEqualTo(reason.getValue());
                assertThat(response.usage().orElseThrow().inputTokens()).isEqualTo(12);
                assertThat(response.usage().orElseThrow().cachedInputTokens()).isEqualTo(3);
                assertThat(response.usage().orElseThrow().outputTokens()).isEqualTo(4);
            }
        }
    }

    @Test
    void rejectsTruncatedMalformedUnknownAndAmbiguousStreams()
            throws Exception
    {
        String text = DeepSeekHttpFixture.textStream("Answer");
        String tool = DeepSeekHttpFixture.toolStream();
        List<String> invalid = List.of(text.replace("data: [DONE]\n\n", ""), text.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":null"),
                text.replace("\"content\":\"Answer\"", "\"unknown\":\"Answer\""), text.replace("\"index\":0", "\"index\":1"),
                text.replace("\"prompt_cache_hit_tokens\":3", "\"prompt_cache_hit_tokens\":30"), tool.replace("CONTRACT-1", "CONTRACT-1\\"),
                tool.replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\""));
        try (ModelClient client = client()) {
            for (String stream : invalid) {
                http.reply(200, stream);
                assertThat(failure(client.generate(request(), value -> {})).kind()).isEqualTo(ModelClientException.Kind.PROTOCOL);
            }
        }
    }

    @Test
    void supportsSseCommentsCrLfAndUnicodeWithoutShowingReasoning()
            throws Exception
    {
        try (ModelClient client = client()) {
            for (String newline : List.of("\n", "\r\n", "\r")) {
                http.reply(200, ": keepalive\n\n" + DeepSeekHttpFixture.textStream("你好 🌍").replace("\n", newline));
                assertThat(complete(client.generate(request(), text -> {})).text()).isEqualTo("你好 🌍");
            }
        }
    }

    @Test
    void normalizesHttpFailuresWithoutRetriesOrLeakingProviderBodies()
            throws Exception
    {
        int[] codes = {401, 402, 429, 422, 503};
        var kinds = List.of(ModelClientException.Kind.AUTHENTICATION_REQUIRED, ModelClientException.Kind.RATE_LIMITED, ModelClientException.Kind.RATE_LIMITED,
                ModelClientException.Kind.INVALID_REQUEST, ModelClientException.Kind.TEMPORARILY_UNAVAILABLE);
        try (ModelClient client = client()) {
            for (int i = 0; i < codes.length; i++) {
                http.reply(codes[i], "{\"error\":{\"message\":\"sk-test-first private\"}}");
                ModelClientException error = failure(client.generate(request(), text -> {}));
                assertThat(error.kind()).isEqualTo(kinds.get(i));
                assertThat(error.deliveryUncertain()).isEqualTo(codes[i] >= 500);
                assertThat(error.getMessage()).doesNotContain("sk-test-first", "private");
                assertThat(error.getCause()).isNull();
            }
            assertThat(http.requests()).hasSize(codes.length);
        }
    }

    @Test
    void rejectsUnsupportedInputsBeforeHttpAndReportsMissingCredentials()
    {
        try (ModelClient client = client()) {
            var image = new ModelInput.UserMessage("Image", List.of(new AgentAttachment("image", "image/png", Path.of("/unread/image.png"))));
            for (var request : List.of(request("Hello").effort("unknown").build(), request("Hello").serviceTier("priority").build(),
                    request("Hello").deadline(Instant.now().minusSeconds(1)).build(), ModelRequest.builder("image", MODEL, image, 128).build(),
                    request("Hello").replayState(new ModelReplayState("anthropic", 1, "{}")).build(),
                    request("Hello").replayState(new ModelReplayState("deepseek", 1, "null")).build())) {
                assertThatThrownBy(() -> client.generate(request, text -> {})).isInstanceOf(ModelClientException.class);
            }
            key.set("");
            assertThat(client.probe(Optional.empty()).readiness().name()).isEqualTo("AUTHENTICATION_REQUIRED");
            assertThat(http.requests()).isEmpty();
        }
    }

    @Test
    void validatesMetadataAndRedactsCatalogLabels()
    {
        try (ModelClient client = client()) {
            for (String body : List.of("not-json", "{\"data\":{}}", "{\"data\":[{\"id\":\"deepseek-a\"},{\"id\":\"deepseek-a\"}]}")) {
                http.reply(200, body);
                assertThatThrownBy(client::models).isInstanceOf(ModelClientException.class);
            }
            http.reply(200, "{\"data\":[{\"id\":\"" + MODEL + "\",\"name\":\"sk-test-first catalog\"}]}");
            assertThat(client.models().getFirst().label()).doesNotContain("sk-test-first");
            key.set("invalid\nkey");
            int count = http.requests().size();
            assertThat(client.probe(Optional.empty()).readiness().name()).isEqualTo("AUTHENTICATION_REQUIRED");
            assertThat(http.requests()).hasSize(count);
        }
    }

    @Test
    void refusesRedirectsAndChecksSelectedModelsWithoutInference()
            throws Exception
    {
        try (ModelClient client = client()) {
            assertThat(client.probe(Optional.of("deepseek-absent")).readiness().name()).isEqualTo("UNSUPPORTED");
            assertThat(http.requests()).hasSize(1);
            http.reply(307, "{}");
            assertThat(failure(client.generate(request(), text -> {}))).isNotNull();
            assertThat(http.requests()).hasSize(2);
        }
    }

    @Test
    void boundsStalledStreamsAndRedactsSplitCredentials()
            throws Exception
    {
        try (ModelClient client = new DeepSeekModelClient(DeepSeekApiConfig.builder(MODEL, key::get).baseUrl(http.baseUrl())
                .requestTimeout(Duration.ofMillis(250)).build())) {
            ModelCall call = client.generate(request("Wait").build(), text -> {});
            http.awaitWaiting();
            assertThat(failure(call).deliveryUncertain()).isTrue();
        }
        http.reply(200, DeepSeekHttpFixture.textStream("sk-test-", "first"));
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
        Throwable failure = call.completion().handle((response, error) -> error).toCompletableFuture().get(3, TimeUnit.SECONDS);
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertThat(failure).isInstanceOf(ModelClientException.class);
        return (ModelClientException) failure;
    }
}

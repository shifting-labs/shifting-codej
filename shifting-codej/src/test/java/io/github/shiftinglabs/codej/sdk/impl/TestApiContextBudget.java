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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shiftinglabs.codej.Access;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.ClaudeApi;
import io.github.shiftinglabs.codej.DeepSeekApi;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.TurnResult;
import io.github.shiftinglabs.codej.cli.spi.context.ContextBudget;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPlan;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompaction;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextAssessment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextSizer;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.sdk.testing.AnthropicHttpFixture;
import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiContextBudget
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Thinking(String reasoning_content) {}
    @TempDir
    Path directory;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InputView(List<JsonNode> messages, String system, List<JsonNode> tools, String reasoning_effort)
    {
        InputView
        {
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OutputView(int max_tokens) {}

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sizingProjectsTheActualActiveWireInputIncludingPrivateBlocksAndToolSchemasWithoutHttp(boolean claude)
            throws Exception
    {
        try (Fixture f = new Fixture(claude)) {
            var captured = new CopyOnWriteArrayList<ModelContextInput>();
            try (ModelClient client = f.client(input -> {
                captured.add(input);
                return Optional.of(new ModelContextAssessment(100000, input.serializedInput().length(), 128, ModelContextAssessment.Accuracy.ESTIMATED));
            })) {
                var history = new ModelHistory(true, List.of());
                var first = ModelRequest.builder("one", f.model, new ModelInput.UserMessage("old-prefix " + "x".repeat(4000), List.of()), 128).history(history).build();
                ModelResponse a = generate(client, first);
                history = append(history, first, a);
                f.toolReply();
                var second = ModelRequest.builder("two", f.model, new ModelInput.UserMessage("kept lookup", List.of()), 128).history(history).replayState(a.replayState()).build();
                ModelResponse b = generate(client, second);
                history = append(history, second, b);
                var results = new ModelInput.ToolResults(b.toolCalls().stream().map(call -> new ModelInput.ToolResult(call.id(), "tool outcome", false)).toList());
                var third = ModelRequest.builder("three", f.model, results, 128).history(history).replayState(b.replayState()).build();
                ModelResponse c = generate(client, third);
                history = append(history, third, c);
                var compacted = client.compact(new ModelCompactionRequest(f.model, history, c.replayState(),
                        new ModelCompaction("prior memory", 2), Optional.empty())).orElseThrow();
                var tools = List.of(new ModelRequest.ToolDefinition("lookup", "Lookup description", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}"));
                var request = ModelRequest.builder("four", f.model, new ModelInput.UserMessage("fresh input", List.of()), 128).history(history)
                        .replayState(compacted).instructions("standing instructions").tools(tools).build();
                int before = f.requests();
                var assessment = client.contextBudget(request).orElseThrow();
                assertThat(f.requests()).isEqualTo(before);
                assertThat(assessment.accuracy()).isEqualTo(ModelContextAssessment.Accuracy.ESTIMATED);
                assertThat(captured).hasSize(1);
                var input = captured.getFirst();
                assertThat(input.providerId()).isEqualTo(claude ? "anthropic" : "deepseek");
                assertThat(input.model()).isEqualTo(f.model);
                assertThat(input.maximumOutputTokens()).isEqualTo(128);
                assertThat(input.serializedInput()).contains("prior memory", "kept lookup", "tool outcome", "fresh input", "Lookup description", "standing instructions")
                        .doesNotContain("old-prefix", "sk-budget-test");
                if (claude) {
                    assertThat(input.serializedInput()).contains("signed-state", "private thought");
                }
                else {
                    assertThat(input.serializedInput()).contains("private thought");
                }
                generate(client, request);
                assertThat(JSON.readValue(input.serializedInput(), InputView.class)).isEqualTo(JSON.readValue(f.lastBody(), InputView.class));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothFacadesAutomaticallyCompactOnlyOnMeasuredPressureAndReuseTheSizedActiveReplay(boolean claude)
            throws Exception
    {
        try (Fixture f = new Fixture(claude)) {
            var options = SessionOptions.in(directory).access(Access.TOOLS_ONLY).build();
            SessionRef saved;
            ModelContextSizer unused = input -> { throw new AssertionError("Sizing is disabled"); };
            try (Agent agent = f.agent(Optional.empty(), unused, context -> Optional.empty()); Session session = agent.startSession(options)) {
                assertThat(session.run(Input.text("old-audit " + "x".repeat(1200)))).isInstanceOf(TurnResult.Completed.class);
                assertThat(session.run(Input.text("kept " + "y".repeat(1200)))).isInstanceOf(TurnResult.Completed.class);
                saved = session.ref().orElseThrow();
            }
            var captured = new CopyOnWriteArrayList<ModelContextInput>();
            ModelContextSizer sizer = input -> {
                captured.add(input);
                return Optional.of(new ModelContextAssessment(2500, input.serializedInput().length(), 64, ModelContextAssessment.Accuracy.ESTIMATED));
            };
            ContextSummarizationPolicy policy = context -> context.contextAssessment().isPresent()
                    ? Optional.of(new ContextSummarizationPlan(1, 64)) : Optional.empty();
            try (Agent agent = f.agent(Optional.of(new ContextBudget(40, true)), sizer, policy); Session session = agent.resumeSession(saved, options)) {
                int before = f.requests();
                f.textReply("budget memory");
                assertThat(session.run(Input.text("fresh input"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.requests()).isEqualTo(before + 2);
                assertThat(JSON.readValue(f.lastBody(), OutputView.class).max_tokens()).isEqualTo(64);
                assertThat(f.lastBody()).contains("budget memory", "kept", "fresh input").doesNotContain("old-audit");
            }
            assertThat(captured).hasSize(3);
            captured.clear();
            try (Agent agent = f.agent(Optional.of(new ContextBudget(40, true)), sizer, policy); Session session = agent.resumeSession(saved, options)) {
                int before = f.requests();
                assertThat(session.run(Input.text("after restart"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.requests()).isEqualTo(before + 1);
                assertThat(captured).hasSize(1);
                assertThat(captured.getFirst().serializedInput()).contains("budget memory").doesNotContain("old-audit");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"true,unknown", "false,unknown", "true,null", "false,null", "true,throw", "false,throw"})
    void unavailableOrInvalidSizingNeverSubmitsHttpAndDiagnosticsStayPrivate(boolean claude, String mode)
            throws Exception
    {
        try (Fixture f = new Fixture(claude); ModelClient client = f.client(input -> switch (mode) {
            case "null" -> null;
            case "throw" -> throw new IllegalStateException("private body sk-budget-test");
            default -> Optional.empty();
        })) {
            var request = ModelRequest.builder("one", f.model, new ModelInput.UserMessage("private body", List.of()), 32).build();
            int before = f.requests();
            if (mode.equals("unknown")) {
                assertThat(client.contextBudget(request)).isEmpty();
            }
            else {
                assertThatThrownBy(() -> client.contextBudget(request)).isInstanceOf(ModelClientException.class)
                        .hasMessageNotContaining("private body").hasMessageNotContaining("sk-budget-test");
            }
            assertThatThrownBy(() -> client.contextBudget(null)).isInstanceOf(NullPointerException.class);
            var expired = ModelRequest.builder("expired", f.model, request.input(), 32).deadline(Instant.MIN).build();
            assertThatThrownBy(() -> client.contextBudget(expired)).isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(f.requests()).isEqualTo(before);
        }
    }

    private static ModelHistory append(ModelHistory history, ModelRequest request, ModelResponse response)
    {
        return history.append(new ModelHistory.Exchange(request.requestId(), response.replayState().providerId(), request.model(), request.input(),
                response.text(), response.toolCalls(), response.stopReason()));
    }

    private static ModelResponse generate(ModelClient client, ModelRequest request)
            throws Exception
    {
        return client.generate(request, text -> {}).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private final class Fixture
            implements AutoCloseable
    {
        final AnthropicHttpFixture anthropic;
        final DeepSeekHttpFixture deepseek;
        final String model;

        Fixture(boolean claude)
                throws Exception
        {
            anthropic = claude ? new AnthropicHttpFixture() : null;
            deepseek = claude ? null : new DeepSeekHttpFixture();
            model = claude ? AnthropicHttpFixture.MODEL : DeepSeekHttpFixture.MODEL;
        }

        ModelClient client(ModelContextSizer sizer)
        {
            return anthropic != null ? new AnthropicModelClient(ClaudeApiConfig.builder(model, () -> "sk-budget-test").baseUrl(anthropic.baseUrl()).contextSizer(sizer).build())
                    : new DeepSeekModelClient(DeepSeekApiConfig.builder(model, () -> "sk-budget-test").baseUrl(deepseek.baseUrl()).contextSizer(sizer).build());
        }

        Agent agent(Optional<ContextBudget> budget, ModelContextSizer sizer, ContextSummarizationPolicy policy)
        {
            if (anthropic != null) {
                var builder = ClaudeApi.builder().model(model).apiKey(() -> "sk-budget-test").baseUrl(anthropic.baseUrl()).contextSizer(sizer).contextSummarization(policy)
                        .profileDirectory(directory.resolve("profile")).stateDirectory(directory.resolve("state"));
                budget.ifPresent(builder::contextBudget);
                return builder.build();
            }
            var builder = DeepSeekApi.builder().model(model).apiKey(() -> "sk-budget-test").baseUrl(deepseek.baseUrl()).contextSizer(sizer).contextSummarization(policy)
                    .profileDirectory(directory.resolve("profile")).stateDirectory(directory.resolve("state"));
            budget.ifPresent(builder::contextBudget);
            return builder.build();
        }

        void textReply(String text)
        {
            if (anthropic != null) {
                anthropic.reply(200, AnthropicHttpFixture.textStream(text));
            }
            else {
                deepseek.reply(200, DeepSeekHttpFixture.textStream(text));
            }
        }

        void toolReply()
        {
            if (anthropic != null) {
                anthropic.reply(200, AnthropicHttpFixture.toolStream(true));
            }
            else {
                deepseek.reply(200, DeepSeekHttpFixture.chunk(DeepSeekHttpFixture.JSON.valueToTree(new Thinking("private thought")), null, false)
                        + DeepSeekHttpFixture.parallelToolStream());
            }
        }

        int requests() { return anthropic != null ? anthropic.requests().size() : deepseek.requests().size(); }

        String lastBody() { return anthropic != null ? anthropic.requests().getLast().body().toString() : deepseek.requests().getLast().body().toString(); }

        @Override
        public void close()
        {
            if (anthropic != null) {
                anthropic.close();
            }
            if (deepseek != null) {
                deepseek.close();
            }
        }
    }
}

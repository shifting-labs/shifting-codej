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
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactor;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPlan;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompaction;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.sdk.testing.AnthropicHttpFixture;
import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiContextCompaction
{
    @TempDir
    Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessageView(String role, JsonNode content)
    {
        MessageView
        {
            requireNonNull(role, "role is null");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BodyView(List<MessageView> messages, String system)
    {
        BodyView { messages = List.copyOf(messages); }
    }

    private record Thinking(String reasoning_content) {}

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void facadesGenerateIsolatedSummariesAndResumeCompactedContext(boolean claude)
            throws Exception
    {
        try (Fixture f = new Fixture(claude)) {
            ContextSummarizationPolicy policy = context -> context.history().exchanges().size() >= 2
                    ? Optional.of(new ContextSummarizationPlan(1, 64)) : Optional.empty();
            var options = SessionOptions.in(directory).access(Access.TOOLS_ONLY).build();
            SessionRef saved;
            try (Agent agent = f.summaryAgent(policy); Session session = agent.startSession(options)) {
                assertThat(session.run(Input.text("original audit " + "x".repeat(12000)))).isInstanceOf(TurnResult.Completed.class);
                assertThat(session.run(Input.text("recent context " + "y".repeat(2000)))).isInstanceOf(TurnResult.Completed.class);
                int before = f.requests();
                f.textReply("generated memory");
                assertThat(session.run(Input.text("fresh input"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.requests()).isEqualTo(before + 2);
                String summaryBody = f.bodies().get(f.bodies().size() - 2);
                BodyView summary = JSON.readValue(summaryBody, BodyView.class);
                assertThat(summary.messages().stream().filter(message -> message.role().equals("user"))).hasSize(1);
                assertThat(summaryBody).contains("original audit", "untrusted context data").doesNotContain("recent context", "fresh input", "generated memory", "sk-context-test");
                assertThat(f.lastBody()).contains("generated memory", "recent context", "fresh input").doesNotContain("original audit");
                saved = session.ref().orElseThrow();
            }
            try (Agent agent = f.summaryAgent(policy); Session session = agent.resumeSession(saved, options)) {
                f.textReply("updated memory");
                assertThat(session.run(Input.text("after restart"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.lastBody()).contains("updated memory", "fresh input", "after restart").doesNotContain("recent context", "original audit");
            }
            try (Agent agent = f.agent(Optional.empty()); Session session = agent.resumeSession(saved, options)) {
                assertThat(session.run(Input.text("policy disabled"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.lastBody()).contains("updated memory", "policy disabled");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void summaryHttpFailureLeavesDurableNativeReplayUsable(boolean claude)
            throws Exception
    {
        try (Fixture f = new Fixture(claude)) {
            var options = SessionOptions.in(directory).access(Access.TOOLS_ONLY).build();
            SessionRef saved;
            try (Agent agent = f.summaryAgent(context -> Optional.of(new ContextSummarizationPlan(0, 64))); Session session = agent.startSession(options)) {
                assertThat(session.run(Input.text("original audit " + "x".repeat(12000)))).isInstanceOf(TurnResult.Completed.class);
                if (claude) {
                    f.anthropic.reply(503, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Unavailable\"}}");
                }
                else {
                    f.deepseek.reply(503, "{\"error\":{\"message\":\"Unavailable\",\"type\":\"server_error\"}}");
                }
                int before = f.requests();
                assertThat(session.run(Input.text("failed fresh input"))).isInstanceOf(TurnResult.Failed.class);
                assertThat(f.requests()).isEqualTo(before + 1);
                saved = session.ref().orElseThrow();
            }
            try (Agent agent = f.agent(Optional.empty()); Session session = agent.resumeSession(saved, options)) {
                assertThat(session.run(Input.text("retry normally"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.lastBody()).contains("original audit", "retry normally").doesNotContain("failed fresh input");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void compactedContextPreservesRetainedToolChainsAndPrivateProviderData(boolean claude)
            throws Exception
    {
        try (Fixture f = new Fixture(claude)) {
            ModelHistory history = new ModelHistory(true, List.of());
            ModelRequest first = f.request("one", new ModelInput.UserMessage("old prefix " + "x".repeat(12000), List.of()), history, Optional.empty());
            ModelResponse a = f.generate(first);
            history = append(history, first, a);
            f.toolChain();
            ModelRequest second = f.request("two", new ModelInput.UserMessage("kept lookup", List.of()), history, Optional.of(a.replayState()));
            ModelResponse b = f.generate(second);
            history = append(history, second, b);
            var results = new ModelInput.ToolResults(b.toolCalls().stream().map(call -> new ModelInput.ToolResult(call.id(), "found " + call.id(), false)).toList());
            ModelRequest third = f.request("three", results, history, Optional.of(b.replayState()));
            ModelResponse c = f.generate(third);
            history = append(history, third, c);
            int before = f.requests();
            var compacted = f.client.compact(new ModelCompactionRequest(f.model, history, c.replayState(), new ModelCompaction("old summary", 2), Optional.empty())).orElseThrow();
            assertThat(f.requests()).isEqualTo(before);
            assertThat(compacted.formatVersion()).isEqualTo(2);
            assertThat(compacted.payload().length()).isLessThan(c.replayState().payload().length());
            assertThat(ContextReplay.nativeReplay(compacted).payload()).contains("kept lookup", "found call-1", "private thought").doesNotContain("old prefix");
            if (claude) {
                assertThat(ContextReplay.nativeReplay(compacted).payload()).contains("signed-state");
            }
            else {
                assertThat(ContextReplay.nativeReplay(compacted).payload()).contains("found call-2");
            }
            ModelRequest next = f.request("four", new ModelInput.UserMessage("next input", List.of()), history, Optional.of(compacted));
            ModelResponse after = f.generate(next);
            assertThat(after.replayState().formatVersion()).isEqualTo(2);
            BodyView sent = JSON.readValue(f.lastBody(), BodyView.class);
            MessageView summary = sent.messages().stream().filter(message -> message.role().equals("user")).findFirst().orElseThrow();
            assertThat(summary.content().toString()).contains("old summary");
            String instructions = sent.system() != null ? sent.system() : sent.messages().stream().filter(message -> message.role().equals("system"))
                    .findFirst().orElseThrow().content().toString();
            assertThat(instructions).contains("standing instructions").doesNotContain("old summary");
            assertThat(next.history().orElseThrow().exchanges()).hasSize(3);
            assertThat(((ModelInput.UserMessage) next.history().orElseThrow().exchanges().getFirst().input()).text()).contains("old prefix");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bothFacadesSupportRepeatedCompactionAndResumeWithoutRewritingAuditHistory(boolean claude)
            throws Exception
    {
        try (Fixture f = new Fixture(claude)) {
            AtomicInteger proposals = new AtomicInteger();
            ContextCompactor policy = context -> {
                assertThat(context.history().complete()).isTrue();
                assertThat(context.replayCharacters()).isPositive();
                assertThat(context.model()).isEqualTo(f.model);
                assertThat(context.turnId()).isNotBlank();
                assertThat(context.cancelled()).isFalse();
                if (context.history().exchanges().size() < 2) {
                    return Optional.empty();
                }
                assertThat(((ModelInput.UserMessage) context.history().exchanges().getFirst().input()).text()).contains("original audit input");
                return Optional.of(new ModelCompaction("summary-" + proposals.incrementAndGet(), 1));
            };
            SessionRef saved;
            var options = SessionOptions.in(directory).access(Access.TOOLS_ONLY).build();
            try (Agent agent = f.agent(Optional.of(policy)); Session session = agent.startSession(options)) {
                assertThat(proposals).hasValue(0);
                assertThat(session.run(Input.text("original audit input " + "x".repeat(12000)))).isInstanceOf(TurnResult.Completed.class);
                assertThat(session.run(Input.text("middle context " + "m".repeat(2000)))).isInstanceOf(TurnResult.Completed.class);
                assertThat(session.run(Input.text("recent input"))).isInstanceOf(TurnResult.Completed.class);
                saved = session.ref().orElseThrow();
            }
            assertThat(proposals).hasValue(1);
            try (Agent agent = f.agent(Optional.of(policy)); Session session = agent.resumeSession(saved, options)) {
                assertThat(session.run(Input.text("fourth input"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.lastBody()).contains("summary-2", "recent input", "fourth input").doesNotContain("original audit input", "middle context");
            }
            assertThat(proposals).hasValue(2);
            try (Agent agent = f.agent(Optional.empty()); Session session = agent.resumeSession(saved, options)) {
                assertThat(session.run(Input.text("resume without policy"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.lastBody()).contains("summary-2", "recent input", "fourth input", "resume without policy");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidLocalCompactionDoesNotSubmitHttpOrExposeCredentials(boolean claude)
            throws Exception
    {
        try (Fixture f = new Fixture(claude)) {
            ModelHistory history = new ModelHistory(true, List.of());
            ModelRequest request = f.request("one", new ModelInput.UserMessage("prefix " + "x".repeat(12000), List.of()), history, Optional.empty());
            ModelResponse response = f.generate(request);
            history = append(history, request, response);
            ModelHistory saved = history;
            int before = f.requests();
            assertThatThrownBy(() -> f.client.compact(new ModelCompactionRequest(f.model, saved, response.replayState(),
                    new ModelCompaction("sk-context-test", 0), Optional.empty()))).isInstanceOf(ModelClientException.class).hasMessageNotContaining("sk-context-test");
            assertThatThrownBy(() -> f.client.compact(new ModelCompactionRequest(f.model, saved, response.replayState(),
                    new ModelCompaction("summary", 0), Optional.of(Instant.MIN)))).isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThatThrownBy(() -> f.client.compact(null)).isInstanceOf(NullPointerException.class);
            assertThat(f.requests()).isEqualTo(before);
            f.client.close();
            assertThatThrownBy(() -> f.client.compact(new ModelCompactionRequest(f.model, saved, response.replayState(),
                    new ModelCompaction("summary", 0), Optional.empty()))).isInstanceOf(RuntimeException.class);
        }
    }

    private static ModelHistory append(ModelHistory history, ModelRequest request, ModelResponse response)
    {
        return history.append(new ModelHistory.Exchange(request.requestId(), response.replayState().providerId(), request.model(), request.input(),
                response.text(), response.toolCalls(), response.stopReason()));
    }

    private final class Fixture
            implements AutoCloseable
    {
        private final AnthropicHttpFixture anthropic;
        private final DeepSeekHttpFixture deepseek;
        private final String model;
        private final ModelClient client;

        Fixture(boolean claude)
                throws Exception
        {
            anthropic = claude ? new AnthropicHttpFixture() : null;
            deepseek = claude ? null : new DeepSeekHttpFixture();
            model = claude ? AnthropicHttpFixture.MODEL : DeepSeekHttpFixture.MODEL;
            if (claude) {
                client = new AnthropicModelClient(ClaudeApiConfig.builder(model, () -> "sk-context-test")
                        .baseUrl(anthropic.baseUrl()).requestTimeout(Duration.ofSeconds(5)).build());
            }
            else {
                client = new DeepSeekModelClient(DeepSeekApiConfig.builder(model, () -> "sk-context-test")
                        .baseUrl(deepseek.baseUrl()).requestTimeout(Duration.ofSeconds(5)).build());
            }
        }

        ModelRequest request(String id, ModelInput input, ModelHistory history, Optional<ModelReplayState> replay)
        {
            var builder = ModelRequest.builder(id, model, input, 128).history(history).instructions("standing instructions");
            replay.ifPresent(builder::replayState);
            return builder.build();
        }

        ModelResponse generate(ModelRequest request)
                throws Exception
        {
            return client.generate(request, text -> {}).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }

        void toolChain()
                throws Exception
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

        List<String> bodies()
        {
            return anthropic != null ? anthropic.requests().stream().map(request -> request.body().toString()).toList()
                    : deepseek.requests().stream().map(request -> request.body().toString()).toList();
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

        Agent summaryAgent(ContextSummarizationPolicy policy) { return agent(Optional.empty(), Optional.of(policy)); }

        Agent agent(Optional<ContextCompactor> policy) { return agent(policy, Optional.empty()); }

        private Agent agent(Optional<ContextCompactor> policy, Optional<ContextSummarizationPolicy> summarization)
        {
            if (anthropic != null) {
                var builder = ClaudeApi.builder().model(model).apiKey(() -> "sk-context-test").baseUrl(anthropic.baseUrl())
                        .stateDirectory(directory.resolve("state")).profileDirectory(directory.resolve("profile"));
                policy.ifPresent(builder::contextCompactor);
                summarization.ifPresent(builder::contextSummarization);
                return builder.build();
            }
            var builder = DeepSeekApi.builder().model(model).apiKey(() -> "sk-context-test").baseUrl(deepseek.baseUrl())
                    .stateDirectory(directory.resolve("state")).profileDirectory(directory.resolve("profile"));
            policy.ifPresent(builder::contextCompactor);
            summarization.ifPresent(builder::contextSummarization);
            return builder.build();
        }

        @Override
        public void close()
        {
            client.close();
            if (anthropic != null) {
                anthropic.close();
            }
            if (deepseek != null) {
                deepseek.close();
            }
        }
    }
}

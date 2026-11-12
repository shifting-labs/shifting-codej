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
package io.github.shiftinglabs.codej.cli.spi.model.consumer;

import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.cli.testing.AbstractTestModelClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** External-package consumer example: the host retains tools; the adapter sees only public model SPI values. */
class TestModelClientSpi
        extends AbstractTestModelClient
{
    @Override
    protected ModelClient client() { return new ScriptedClient(); }

    @Override
    protected ModelRequest request() { return request("answer", "Hello"); }

    @Override
    protected ModelResponse expectedResponse() { return answer(); }

    @Override
    protected List<String> expectedTextDeltas() { return List.of("Hello", " world"); }

    @Override
    protected ModelCall pendingCall(ModelClient client) { return client.generate(request("pending", "Wait"), text -> {}); }

    @Override
    protected ModelCall failedCall(ModelClient client) { return client.generate(request("failure", "Hello"), text -> {}); }

    @Test
    void hostExecutesItsToolThenContinuesWithTheSavedReplayState()
            throws Exception
    {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = Tool.withSchema("read_issue", "Reads one issue", "{\"type\":\"object\"}", arguments -> {
            executions.incrementAndGet();
            return "Issue is open";
        });
        ModelRequest.ToolDefinition definition = new ModelRequest.ToolDefinition(tool.name(), tool.description(), tool.inputSchema());
        try (ScriptedClient client = new ScriptedClient()) {
            ModelRequest first = ModelRequest.builder("tools", "fixture-model", new ModelInput.UserMessage("Read the issue", List.of()), 128)
                    .instructions("Use the supplied host tools.").tools(List.of(definition)).effort("low").serviceTier("standard")
                    .deadline(Instant.parse("2100-01-01T00:00:00Z")).build();
            ModelResponse response = client.generate(first, text -> {}).completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThat(response.stopReason()).isEqualTo(ModelResponse.StopReason.TOOL_CALLS);
            assertThat(executions).hasValue(0);
            ModelResponse.ToolCall call = response.toolCalls().getFirst();
            assertThat(call.name()).isEqualTo(tool.name());
            // The host would apply its policy/approval checks here, before invoking its handler.
            String result = tool.call(call.arguments());
            ModelReplayState loaded = new ModelReplayState(response.replayState().providerId(),
                    response.replayState().formatVersion(), response.replayState().payload());
            ModelInput input = new ModelInput.ToolResults(List.of(new ModelInput.ToolResult(call.id(), result, false)));
            ModelRequest next = ModelRequest.builder("continued", "fixture-model", input, 128)
                    .instructions(first.instructions()).tools(first.tools()).replayState(loaded).build();
            List<String> text = new ArrayList<>();
            ModelResponse finalResponse = client.generate(next, text::add).completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThat(finalResponse.text()).isEqualTo("Hello world");
            assertThat(finalResponse.stopReason()).isEqualTo(ModelResponse.StopReason.COMPLETED);
            assertThat(text).containsExactly("Hello", " world");
            assertThat(executions).hasValue(1);
            assertThat(client.requests).containsExactly(first, next);
            assertThat(next.replayState().orElseThrow().payload()).isEqualTo("private pending call: call-1");
            assertThat(((ModelInput.ToolResults) next.input()).results())
                    .containsExactly(new ModelInput.ToolResult("call-1", "Issue is open", false));
        }
    }

    @Test
    void optionalMetadataDefaultsAndConstructionNeverGenerateAnAnswer()
    {
        try (ScriptedClient client = new ScriptedClient()) {
            assertThat(client.capabilities("fixture-model").toolCalls()).isTrue();
            assertThat(client.probe(Optional.empty()).available()).isTrue();
            assertThat(client.models()).isEmpty();
            assertThat(client.quota().plan()).isEmpty();
            assertThat(client.quota().windows()).isEmpty();
            assertThat(client.quota().message()).contains("This model provider does not report account limits.");
            assertThat(client.requests).isEmpty();
        }
    }

    @Test
    void unsupportedOptionsForeignReplayAndExpiredDeadlinesAreRejectedBeforeSubmission()
    {
        try (ScriptedClient client = new ScriptedClient()) {
            ModelInput input = new ModelInput.UserMessage("Hello", List.of());
            List<ModelRequest> invalid = List.of(
                    ModelRequest.builder("wrong-model", "unknown", input, 128).build(),
                    ModelRequest.builder("effort", "fixture-model", input, 128).effort("unknown").build(),
                    ModelRequest.builder("tier", "fixture-model", input, 128).serviceTier("unknown").build(),
                    ModelRequest.builder("expired", "fixture-model", input, 128).deadline(Instant.EPOCH).build(),
                    ModelRequest.builder("foreign", "fixture-model", input, 128).replayState(new ModelReplayState("other", 1, "private")).build(),
                    ModelRequest.builder("version", "fixture-model", input, 128).replayState(new ModelReplayState("fixture", 2, "private")).build(),
                    ModelRequest.builder("batch", "fixture-model", new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("wrong", "", true))), 128)
                            .replayState(new ModelReplayState("fixture", 1, "private pending call: call-1")).build());
            for (ModelRequest request : invalid) {
                assertThatThrownBy(() -> client.generate(request, text -> {})).isInstanceOf(ModelClientException.class);
            }
            assertThat(client.requests).isEmpty();
        }
    }

    private static ModelRequest request(String id, String text)
    {
        return ModelRequest.builder(id, "fixture-model", new ModelInput.UserMessage(text, List.of()), 128).build();
    }

    private static ModelResponse answer()
    {
        return new ModelResponse("Hello world", List.of(), ModelResponse.StopReason.COMPLETED,
                new ModelReplayState("fixture", 1, "private signed content"),
                Optional.of(new ProviderUsageSample(Optional.of("response-1"), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, 12, 3, 4, Optional.empty())));
    }

    /** No threads, processes, files, SDKs or network; this is a binding example, not the future shared engine. */
    private static final class ScriptedClient
            implements ModelClient
    {
        private final List<ModelRequest> requests = new ArrayList<>();
        private final List<PendingCall> calls = new ArrayList<>();
        private boolean closed;

        @Override
        public ModelCapabilities capabilities(String model)
        {
            checkText(model, "model");
            if (!model.equals("fixture-model")) {
                throw invalid();
            }
            return new ModelCapabilities(true, Set.of(), Set.of("low"), Set.of("standard"));
        }

        @Override
        public AgentProviderStatus probe(Optional<String> model)
        {
            requireNonNull(model, "model is null").ifPresent(this::capabilities);
            return new AgentProviderStatus(new AgentLaneId("fixture-api"), AgentProviderStatus.Readiness.READY,
                    Optional.empty(), "Ready", AgentCapabilities.none());
        }

        @Override
        public synchronized ModelCall generate(ModelRequest request, ModelEventSink events)
        {
            requireNonNull(request, "request is null");
            requireNonNull(events, "events is null");
            ModelCapabilities capabilities = capabilities(request.model());
            if (closed || request.effort().filter(value -> !capabilities.efforts().contains(value)).isPresent()
                    || request.serviceTier().filter(value -> !capabilities.serviceTiers().contains(value)).isPresent()
                    || request.deadline().filter(value -> !value.isAfter(Instant.now())).isPresent()) {
                throw invalid();
            }
            request.replayState().ifPresent(state -> {
                if (!state.providerId().equals("fixture") || state.formatVersion() != 1) {
                    throw invalid();
                }
            });
            if (request.input() instanceof ModelInput.ToolResults batch
                    && (!request.replayState().orElseThrow().payload().equals("private pending call: call-1")
                    || batch.results().size() != 1 || !batch.results().getFirst().callId().equals("call-1"))) {
                throw invalid();
            }
            requests.add(request);
            PendingCall call = new PendingCall();
            calls.add(call);
            switch (request.requestId()) {
                case "pending" -> { return call; }
                case "failure" -> call.result.completeExceptionally(new ModelClientException(
                        ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "Connection lost", true));
                case "tools" -> call.result.complete(new ModelResponse("", List.of(new ModelResponse.ToolCall("call-1", "read_issue", "{}")),
                        ModelResponse.StopReason.TOOL_CALLS, new ModelReplayState("fixture", 1, "private pending call: call-1"), Optional.empty()));
                default -> {
                    try {
                        events.textDelta("Hello");
                        events.textDelta(" world");
                        call.result.complete(answer());
                    }
                    catch (RuntimeException failure) {
                        call.result.completeExceptionally(new ModelClientException(ModelClientException.Kind.PROTOCOL, "Event consumer failed", true));
                    }
                }
            }
            return call;
        }

        @Override
        public synchronized void close()
        {
            if (!closed) {
                closed = true;
                calls.forEach(PendingCall::cancel);
            }
        }

        private static ModelClientException invalid()
        {
            return new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, "Request is unsupported", false);
        }
    }

    private static final class PendingCall
            implements ModelCall
    {
        private final CompletableFuture<ModelResponse> result = new CompletableFuture<>();

        @Override
        public CompletionStage<ModelResponse> completion() { return result.minimalCompletionStage(); }

        @Override
        public void cancel() { result.cancel(false); }
    }
}

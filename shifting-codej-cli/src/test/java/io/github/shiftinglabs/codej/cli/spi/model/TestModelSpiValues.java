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
package io.github.shiftinglabs.codej.cli.spi.model;

import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestModelSpiValues
{
    private static final ModelInput INPUT = new ModelInput.UserMessage("Hello", List.of());
    private static final ModelReplayState REPLAY = new ModelReplayState("fixture-api", 1, "private signed content");

    @Test
    void capabilitiesCopyAllSetsAndExposeConservativeDefaults()
    {
        Set<String> media = new HashSet<>(Set.of("image/png"));
        Set<String> efforts = new HashSet<>(Set.of("low"));
        Set<String> tiers = new HashSet<>(Set.of("standard"));
        ModelCapabilities capabilities = new ModelCapabilities(true, media, efforts, tiers);
        media.clear();
        efforts.clear();
        tiers.clear();
        assertThat(capabilities.toolCalls()).isTrue();
        assertThat(capabilities.attachmentMediaTypes()).containsExactly("image/png");
        assertThat(capabilities.efforts()).containsExactly("low");
        assertThat(capabilities.serviceTiers()).containsExactly("standard");
        assertThatThrownBy(() -> capabilities.attachmentMediaTypes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> capabilities.efforts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> capabilities.serviceTiers().clear()).isInstanceOf(UnsupportedOperationException.class);
        ModelCapabilities defaults = ModelCapabilities.textOnly();
        assertThat(defaults.toolCalls()).isFalse();
        assertThat(defaults.attachmentMediaTypes()).isEmpty();
        assertThat(defaults.efforts()).isEmpty();
        assertThat(defaults.serviceTiers()).isEmpty();
    }

    @Test
    void capabilitiesRejectMissingAndBlankSetValues()
    {
        assertThatThrownBy(() -> new ModelCapabilities(false, null, Set.of(), Set.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelCapabilities(false, Set.of(), null, Set.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelCapabilities(false, Set.of(), Set.of(), null)).isInstanceOf(NullPointerException.class);
        Set<String> missing = new HashSet<>();
        missing.add(null);
        for (Set<String> values : List.of(Set.of(" "), missing)) {
            assertThatThrownBy(() -> new ModelCapabilities(false, values, Set.of(), Set.of())).isInstanceOfAny(
                    IllegalArgumentException.class, NullPointerException.class);
            assertThatThrownBy(() -> new ModelCapabilities(false, Set.of(), values, Set.of())).isInstanceOfAny(
                    IllegalArgumentException.class, NullPointerException.class);
            assertThatThrownBy(() -> new ModelCapabilities(false, Set.of(), Set.of(), values)).isInstanceOfAny(
                    IllegalArgumentException.class, NullPointerException.class);
        }
    }

    @Test
    void replayRoundTripsOpaqueSpellingAndHidesItsPayloadInDiagnostics()
    {
        String payload = " signed\ncontent:\u03bb:{not JSON} ";
        ModelReplayState replay = new ModelReplayState("claude-api", 7, payload);
        ModelReplayState loaded = new ModelReplayState(replay.providerId(), replay.formatVersion(), replay.payload());
        assertThat(loaded.providerId()).isEqualTo("claude-api");
        assertThat(loaded.formatVersion()).isEqualTo(7);
        assertThat(loaded.payload()).isEqualTo(payload);
        assertThat(loaded.toString()).isEqualTo("ModelReplayState[providerId=claude-api, formatVersion=7, payload=<private>]");
        assertThat(loaded.toString()).doesNotContain("signed", "not JSON");
        assertThat(new ModelReplayState("fixture", 1, "").payload()).isEmpty();
    }

    @Test
    void replayRejectsInvalidIdentityAndVersionWithoutEchoingPayloads()
    {
        for (String invalid : List.of("", " ", "Secret\nvalue", "../other", "provider:profile", "Upper")) {
            assertThatThrownBy(() -> new ModelReplayState(invalid, 1, "private"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid providerId");
        }
        for (int version : List.of(0, -1)) {
            assertThatThrownBy(() -> new ModelReplayState("fixture", version, "private"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("formatVersion must be positive");
        }
        assertThatThrownBy(() -> new ModelReplayState(null, 1, "private")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelReplayState("fixture", 1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void userMessagesCopyAttachmentsWithoutReadingTheFilesystem()
    {
        AgentAttachment attachment = new AgentAttachment("attachment", "image.png", "image/png", Path.of("/absent-model-spi-test/image.png"));
        List<AgentAttachment> attachments = new ArrayList<>(List.of(attachment));
        ModelInput.UserMessage message = new ModelInput.UserMessage("", attachments);
        attachments.clear();
        assertThat(message.text()).isEmpty();
        assertThat(message.attachments()).containsExactly(attachment);
        assertThatThrownBy(() -> message.attachments().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ModelInput.UserMessage(" ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelInput.UserMessage(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelInput.UserMessage("Hello", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelInput.UserMessage("Hello", Arrays.asList((AgentAttachment) null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toolResultsPreserveOrderErrorsAndEmptyOutput()
    {
        ModelInput.ToolResult first = new ModelInput.ToolResult("first", "", false);
        ModelInput.ToolResult second = new ModelInput.ToolResult("second", "Denied", true);
        List<ModelInput.ToolResult> results = new ArrayList<>(List.of(first, second));
        ModelInput.ToolResults batch = new ModelInput.ToolResults(results);
        results.clear();
        assertThat(batch.results()).containsExactly(first, second);
        assertThat(first.callId()).isEqualTo("first");
        assertThat(first.text()).isEmpty();
        assertThat(first.error()).isFalse();
        assertThat(second.error()).isTrue();
        assertThat(second.text()).isEqualTo("Denied");
        assertThatThrownBy(() -> batch.results().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ModelInput.ToolResults(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelInput.ToolResults(List.of(first, first))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelInput.ToolResults(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelInput.ToolResults(Arrays.asList(first, null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelInput.ToolResult(" ", "", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelInput.ToolResult(null, "", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelInput.ToolResult("call", null, false)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void requestDefaultsNeedNoProviderOrProcess()
    {
        ModelRequest request = builder().build();
        assertThat(request.requestId()).isEqualTo("request");
        assertThat(request.model()).isEqualTo("model");
        assertThat(request.input()).isSameAs(INPUT);
        assertThat(request.maximumOutputTokens()).isEqualTo(128);
        assertThat(request.instructions()).isEmpty();
        assertThat(request.replayState()).isEmpty();
        assertThat(request.tools()).isEmpty();
        assertThat(request.effort()).isEmpty();
        assertThat(request.serviceTier()).isEmpty();
        assertThat(request.deadline()).isEmpty();
    }

    @Test
    void requestCopiesToolsAndSnapshotsReusableBuilderState()
    {
        ModelRequest.ToolDefinition tool = new ModelRequest.ToolDefinition("lookup", "Looks up an issue", " { \"type\": \"object\" } ");
        List<ModelRequest.ToolDefinition> tools = new ArrayList<>(List.of(tool));
        Instant deadline = Instant.parse("2100-01-01T00:00:00Z");
        ModelRequest.Builder builder = builder().instructions("First").replayState(REPLAY).tools(tools).effort("low").serviceTier("standard").deadline(deadline);
        ModelRequest request = builder.build();
        tools.clear();
        builder.instructions("Next").tools(List.of()).effort("high").serviceTier("priority").deadline(Instant.EPOCH)
                .replayState(new ModelReplayState("fixture-api", 2, "next"));
        assertThat(request.instructions()).isEqualTo("First");
        assertThat(request.replayState()).containsSame(REPLAY);
        assertThat(request.tools()).containsExactly(tool);
        assertThat(tool.name()).isEqualTo("lookup");
        assertThat(tool.description()).isEqualTo("Looks up an issue");
        assertThat(tool.inputSchema()).isEqualTo(" { \"type\": \"object\" } ");
        assertThat(request.effort()).contains("low");
        assertThat(request.serviceTier()).contains("standard");
        assertThat(request.deadline()).contains(deadline);
        assertThatThrownBy(() -> request.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(builder.build().instructions()).isEqualTo("Next");
    }

    @Test
    void requestRejectsMissingIdentityInputAndTokenBound()
    {
        for (String invalid : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> ModelRequest.builder(invalid, "model", INPUT, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ModelRequest.builder("request", invalid, INPUT, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ModelRequest.builder("request", "model", null, 1)).isInstanceOf(NullPointerException.class);
        for (int maximum : List.of(0, -1)) {
            assertThatThrownBy(() -> ModelRequest.builder("request", "model", INPUT, maximum)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requestRejectsInvalidOptionsAndDuplicateToolNames()
    {
        assertThatThrownBy(() -> builder().instructions(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().replayState(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().deadline(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().tools(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().tools(Arrays.asList((ModelRequest.ToolDefinition) null))).isInstanceOf(NullPointerException.class);
        ModelRequest.ToolDefinition first = new ModelRequest.ToolDefinition("lookup", "First", "{}");
        ModelRequest.ToolDefinition second = new ModelRequest.ToolDefinition("lookup", "Second", "{}");
        assertThatThrownBy(() -> builder().tools(List.of(first, second))).isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate tool name");
        for (String invalid : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> builder().effort(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder().serviceTier(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void toolContinuationRequiresReplay()
    {
        ModelInput.ToolResults input = new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call", "result", false)));
        assertThatThrownBy(() -> ModelRequest.builder("request", "model", input, 1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("tool results require replay state");
        assertThat(ModelRequest.builder("request", "model", input, 1).replayState(REPLAY).build().input()).isSameAs(input);
    }

    @Test
    void schemasAndArgumentsMustBeSingleObjectsAndNeverLeakParseErrors()
    {
        for (String invalid : List.of("", " ", "[]", "null", "1", "\"text\"", "{} {}", "{\"secret\":\"private", "{", "true")) {
            assertThatThrownBy(() -> new ModelRequest.ToolDefinition("lookup", "Lookup", invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("inputSchema must be a JSON object").hasNoCause();
            assertThatThrownBy(() -> new ModelResponse.ToolCall("call", "lookup", invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("tool arguments must be a JSON object").hasNoCause();
        }
        assertThatThrownBy(() -> new ModelRequest.ToolDefinition("lookup", "Lookup", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelResponse.ToolCall("call", "lookup", null)).isInstanceOf(NullPointerException.class);
        String arguments = " { \"key\": \"ENG-1\" } ";
        ModelResponse.ToolCall call = new ModelResponse.ToolCall("call", "lookup", arguments);
        assertThat(call.id()).isEqualTo("call");
        assertThat(call.name()).isEqualTo("lookup");
        assertThat(call.arguments()).isSameAs(arguments);
    }

    @Test
    void toolDefinitionsAndCallsRejectBlankIdentities()
    {
        for (String invalid : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> new ModelRequest.ToolDefinition(invalid, "Lookup", "{}")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ModelRequest.ToolDefinition("lookup", invalid, "{}")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ModelResponse.ToolCall(invalid, "lookup", "{}")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ModelResponse.ToolCall("call", invalid, "{}")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void responseCopiesCompleteToolBatchAndKeepsFinalUsage()
    {
        ModelResponse.ToolCall first = new ModelResponse.ToolCall("call-1", "lookup", "{}");
        ModelResponse.ToolCall second = new ModelResponse.ToolCall("call-2", "lookup", "{}");
        List<ModelResponse.ToolCall> calls = new ArrayList<>(List.of(first, second));
        ProviderUsageSample usage = new ProviderUsageSample(Optional.of("response"), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                10, 3, 5, Optional.of(new BigDecimal("0.01")));
        ModelResponse response = new ModelResponse("Checking", calls, ModelResponse.StopReason.TOOL_CALLS, REPLAY, Optional.of(usage));
        calls.clear();
        assertThat(response.text()).isEqualTo("Checking");
        assertThat(response.toolCalls()).containsExactly(first, second);
        assertThat(response.stopReason()).isEqualTo(ModelResponse.StopReason.TOOL_CALLS);
        assertThat(response.replayState()).isSameAs(REPLAY);
        assertThat(response.usage()).contains(usage);
        assertThatThrownBy(() -> response.toolCalls().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonToolStopReasonsCannotAccidentallyAuthorizeToolExecution()
    {
        ModelResponse.ToolCall call = new ModelResponse.ToolCall("call", "lookup", "{}");
        for (ModelResponse.StopReason reason : ModelResponse.StopReason.values()) {
            if (reason == ModelResponse.StopReason.TOOL_CALLS) {
                assertThatThrownBy(() -> new ModelResponse("", List.of(), reason, REPLAY, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
            }
            else {
                ModelResponse response = new ModelResponse("", List.of(), reason, REPLAY, Optional.empty());
                assertThat(response.stopReason()).isEqualTo(reason);
                assertThat(response.toolCalls()).isEmpty();
                assertThat(response.usage()).isEmpty();
                assertThatThrownBy(() -> new ModelResponse("", List.of(call), reason, REPLAY, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> new ModelResponse("", List.of(call, call), ModelResponse.StopReason.TOOL_CALLS, REPLAY, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate tool call ID");
    }

    @Test
    void responseRejectsMissingValuesAndNonFinalUsage()
    {
        assertThatThrownBy(() -> new ModelResponse(null, List.of(), ModelResponse.StopReason.COMPLETED, REPLAY, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelResponse("", null, ModelResponse.StopReason.COMPLETED, REPLAY, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelResponse("", Arrays.asList((ModelResponse.ToolCall) null), ModelResponse.StopReason.COMPLETED, REPLAY, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelResponse("", List.of(), null, REPLAY, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelResponse("", List.of(), ModelResponse.StopReason.COMPLETED, null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelResponse("", List.of(), ModelResponse.StopReason.COMPLETED, REPLAY, null)).isInstanceOf(NullPointerException.class);
        for (ProviderUsageSample.Semantics semantics : ProviderUsageSample.Semantics.values()) {
            if (semantics != ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION) {
                ProviderUsageSample usage = new ProviderUsageSample(Optional.empty(), semantics, 0, 0, 0, Optional.empty());
                assertThatThrownBy(() -> new ModelResponse("", List.of(), ModelResponse.StopReason.COMPLETED, REPLAY, Optional.of(usage)))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("usage must be final for this model invocation");
            }
        }
    }

    @Test
    void normalizedFailuresPreserveKindAndUncertaintyWithoutVendorCauses()
    {
        for (ModelClientException.Kind kind : ModelClientException.Kind.values()) {
            ModelClientException failure = new ModelClientException(kind, "Safe diagnostic", true);
            assertThat(failure.kind()).isEqualTo(kind);
            assertThat(failure.deliveryUncertain()).isTrue();
            assertThat(failure.getMessage()).isEqualTo("Safe diagnostic");
            assertThat(failure.getCause()).isNull();
        }
        assertThat(new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, "Rejected", false).deliveryUncertain()).isFalse();
        assertThatThrownBy(() -> new ModelClientException(null, "Rejected", false)).isInstanceOf(NullPointerException.class);
        for (String invalid : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> new ModelClientException(ModelClientException.Kind.PROTOCOL, invalid, true)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static ModelRequest.Builder builder()
    {
        return ModelRequest.builder("request", "model", INPUT, 128);
    }
}

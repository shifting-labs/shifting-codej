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

import io.github.shiftinglabs.codej.cli.spi.model.ModelContextAssessment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextSizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBuiltinContextSizer
{
    private static final String CLAUDE = """
            {"messages":[{"role":"user","content":[{"type":"text","text":"Hello 世界 😀"}]}],"system":"Instructions"}
            """;
    private static final String DEEPSEEK = """
            {"messages":[{"role":"user","content":"Hello 世界 😀"}]}
            """;

    @ParameterizedTest
    @CsvSource({
            "claude-fable-5-2,1000000,128000", "claude-opus-5-5,1000000,128000",
            "claude-sonnet-5-5,1000000,128000", "claude-haiku-5-5,1000000,128000",
            "claude-fable-5-1,1000000,128000", "claude-fable-5,1000000,128000",
            "claude-opus-5,1000000,128000", "claude-sonnet-5,1000000,128000",
            "claude-opus-4-8,1000000,128000", "claude-opus-4-7,1000000,128000",
            "claude-opus-4-6,1000000,128000", "claude-sonnet-4-6,1000000,128000",
            "claude-opus-4-5,200000,64000", "claude-opus-4-5-20251101,200000,64000",
            "claude-sonnet-4-5,200000,64000", "claude-sonnet-4-5-20250929,200000,64000",
            "claude-haiku-4-5,200000,64000", "claude-haiku-4-5-20251001,200000,64000",
            "deepseek-flash,1048576,393216", "deepseek-v4-pro,1048576,393216",
            "deepseek-v4-flash,1048576,393216", "deepseek-v4-flash-vision-exp,1048576,393216"})
    void catalogUsesReviewedExplicitLimitsAndHonestEstimates(String model, long window, int output)
    {
        boolean claude = model.startsWith("claude");
        var assessment = sizer(claude).assess(input(claude, model, claude ? CLAUDE : DEEPSEEK, 1)).orElseThrow();
        assertThat(assessment.contextWindowTokens()).isEqualTo(window);
        assertThat(assessment.maximumOutputTokens()).isEqualTo(output);
        assertThat(assessment.accuracy()).isEqualTo(ModelContextAssessment.Accuracy.ESTIMATED);
        assertThat(assessment.fits(output, 1024)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-future", "claude-sonnet-4-5-20990101", "claude-sonnet-4-6-custom", "deepseek-future", "deepseek-chat", "deepseek-reasoner", "custom/model"})
    void unknownIdentifiersNeverInheritLimitsFromPrefixes(String model)
    {
        assertThat(BuiltinContextSizer.CLAUDE.assess(input(true, model, CLAUDE, 1))).isEmpty();
        assertThat(BuiltinContextSizer.DEEPSEEK.assess(input(false, model, DEEPSEEK, 1))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void countsUtf8WithoutDependingOnOutputCapAndHandlesOversizedInputs(boolean claude)
    {
        String model = model(claude);
        String serialized = claude ? CLAUDE : DEEPSEEK;
        var one = sizer(claude).assess(input(claude, model, serialized, 1)).orElseThrow();
        assertThat(one.inputTokens()).isEqualTo(serialized.getBytes(StandardCharsets.UTF_8).length + 1024 + 64 + (claude ? 32 : 0));
        assertThat(sizer(claude).assess(input(claude, model, serialized, Integer.MAX_VALUE))).contains(one);
        String large = serialized.replace("Hello 世界 😀", "界😀".repeat(160000));
        var oversized = sizer(claude).assess(input(claude, model, large, 1)).orElseThrow();
        assertThat(oversized.inputTokens()).isGreaterThan(oversized.contextWindowTokens());
        assertThat(oversized.fits(1, 0)).isFalse();
        assertThat(sizer(claude).assess(new ModelContextInput("foreign", model, serialized, 1, Optional.empty()))).isEmpty();
    }

    @Test
    void includesClaudeNativeReasoningToolArgumentsResultsInstructionsAndSchemas()
    {
        String serialized = """
                {"system":"policy","messages":[
                  {"role":"assistant","content":[
                    {"type":"thinking","thinking":"private thought","signature":"signature"},
                    {"type":"redacted_thinking","data":"opaque-state"},
                    {"type":"tool_use","id":"call","name":"lookup","input":{"type":"image","nested":[1,2]}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"call","content":"tool outcome","is_error":true}]}],
                 "tools":[{"name":"lookup","description":"description","input_schema":{"type":"object","properties":{"file":{"type":"string"}}}}]}
                """;
        var report = BuiltinContextSizer.CLAUDE.assess(input(true, model(true), serialized, 128)).orElseThrow();
        assertThat(report.inputTokens()).isEqualTo(serialized.getBytes(StandardCharsets.UTF_8).length + 1024 + 2 * 64 + 4 * 32 + 256);
    }

    @Test
    void includesDeepSeekReasoningFunctionCallsResultsAndSchemas()
    {
        String serialized = """
                {"messages":[{"role":"system","content":"policy"},
                  {"role":"assistant","content":null,"reasoning_content":"private thought",
                    "tool_calls":[{"id":"call","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"call","content":"tool outcome"}],
                 "tools":[{"type":"function","function":{"name":"lookup","description":"description","parameters":{"type":"object"}}}],
                 "reasoning_effort":"high"}
                """;
        var report = BuiltinContextSizer.DEEPSEEK.assess(input(false, model(false), serialized, 128)).orElseThrow();
        assertThat(report.inputTokens()).isEqualTo(serialized.getBytes(StandardCharsets.UTF_8).length + 1024 + 3 * 64 + 256);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"data\":\"AA==\"}}",
            "{\"type\":\"document\",\"source\":{\"type\":\"url\",\"url\":\"https://private\"}}",
            "{\"type\":\"future\",\"text\":\"private\"}",
            "{\"type\":\"text\",\"text\":\"private\",\"future\":true}",
            "{\"type\":\"text\",\"text\":42}", "{\"type\":\"thinking\",\"thinking\":\"private\"}",
            "{\"type\":\"tool_result\",\"tool_use_id\":\"call\",\"content\":[{\"type\":\"image\"}]}"})
    void unsupportedClaudeBlocksAndUnknownFieldsStayUnknown(String block)
    {
        String serialized = "{\"system\":\"\",\"messages\":[{\"role\":\"user\",\"content\":[" + block + "]}]}";
        assertThat(BuiltinContextSizer.CLAUDE.assess(input(true, model(true), serialized, 1))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://private\"}}]}",
            "{\"role\":\"assistant\",\"content\":\"ok\",\"future\":{\"modality\":\"image\"}}",
            "{\"role\":\"future\",\"content\":\"private\"}", "{\"role\":\"user\",\"content\":42}",
            "{\"role\":\"user\",\"content\":null}", "{\"role\":\"tool\",\"content\":\"result\"}"})
    void unsupportedDeepSeekMessagesStayUnknown(String message)
    {
        assertThat(BuiltinContextSizer.DEEPSEEK.assess(input(false, model(false), "{\"messages\":[" + message + "]}", 1))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedProjectionsStayUnknownAndCancellationIsCooperative(boolean claude)
    {
        for (String malformed : new String[] {"null", "{}", "{\"messages\":[]}", "{private", (claude ? CLAUDE : DEEPSEEK) + " {}",
                "{\"messages\":[],\"messages\":[]}", "{\"messages\":[null]}"}) {
            assertThat(sizer(claude).assess(input(claude, model(claude), malformed, 1))).isEmpty();
        }
        var expired = new ModelContextInput(claude ? "anthropic" : "deepseek", model(claude), claude ? CLAUDE : DEEPSEEK, 1, Optional.of(Instant.EPOCH));
        assertThatThrownBy(() -> sizer(claude).assess(expired)).isInstanceOf(CancellationException.class);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> sizer(claude).assess(input(claude, model(claude), claude ? CLAUDE : DEEPSEEK, 1))).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
        assertThatThrownBy(() -> sizer(claude).assess(null)).isInstanceOf(NullPointerException.class);
    }

    private static ModelContextSizer sizer(boolean claude) { return claude ? BuiltinContextSizer.CLAUDE : BuiltinContextSizer.DEEPSEEK; }

    private static String model(boolean claude) { return claude ? "claude-sonnet-4-5" : "deepseek-flash"; }

    private static ModelContextInput input(boolean claude, String model, String serialized, int output)
    {
        return new ModelContextInput(claude ? "anthropic" : "deepseek", model, serialized, output, Optional.empty());
    }
}

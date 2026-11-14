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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestModelHistory
{
    private static final ModelInput.UserMessage USER = new ModelInput.UserMessage("Private prompt", List.of());
    private static final ModelResponse.ToolCall CALL = new ModelResponse.ToolCall("call", "lookup", "{}");

    @Test
    void copiesValuesAndAppendDoesNotMutateExistingSnapshots()
    {
        var supplied = new ArrayList<ModelHistory.Exchange>();
        ModelHistory empty = new ModelHistory(true, supplied);
        ModelHistory.Exchange exchange = exchange("request", USER, List.of(), ModelResponse.StopReason.COMPLETED);
        supplied.add(exchange);
        ModelHistory next = empty.append(exchange);
        assertThat(empty.complete()).isTrue();
        assertThat(empty.exchanges()).isEmpty();
        assertThat(next.exchanges()).containsExactly(exchange);
        assertThat(next).isEqualTo(new ModelHistory(true, List.of(exchange))).hasSameHashCodeAs(new ModelHistory(true, List.of(exchange)));
        assertThat(next).isNotEqualTo(empty).isNotEqualTo(new ModelHistory(false, List.of(exchange))).isNotEqualTo("other");
        assertThat(next.toString()).doesNotContain("Private prompt", "Private answer");
        assertThat(exchange.toString()).doesNotContain("Private prompt", "Private answer");
        assertThat(exchange.requestId()).isEqualTo("request");
        assertThat(exchange.providerId()).isEqualTo("provider");
        assertThat(exchange.model()).isEqualTo("model");
        assertThat(exchange.input()).isSameAs(USER);
        assertThat(exchange.text()).isEqualTo("Private answer");
        assertThat(exchange.stopReason()).isEqualTo(ModelResponse.StopReason.COMPLETED);
        assertThatThrownBy(() -> next.exchanges().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> next.append(exchange)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> empty.append(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelHistory(true, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelHistory(true, Arrays.asList((ModelHistory.Exchange) null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void exactOrderedToolBatchesAreValidatedAcrossEveryExchange()
    {
        var second = new ModelResponse.ToolCall("second", "lookup", "{}");
        var calls = new ArrayList<>(List.of(CALL, second));
        var assistant = exchange("request", USER, calls, ModelResponse.StopReason.TOOL_CALLS);
        calls.clear();
        ModelHistory pending = new ModelHistory(true, List.of(assistant));
        ModelInput.ToolResults results = results("call", "second");
        pending.validateNext(results);
        assertThat(assistant.toolCalls()).containsExactly(CALL, second);
        assertThatThrownBy(() -> assistant.toolCalls().clear()).isInstanceOf(UnsupportedOperationException.class);
        for (ModelInput invalid : List.of(USER, results("call"), results("second", "call"), results("call", "second", "extra"))) {
            assertThatThrownBy(() -> pending.validateNext(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pending.append(exchange("next", invalid, List.of(), ModelResponse.StopReason.COMPLETED)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ModelHistory complete = pending.append(exchange("next", results, List.of(), ModelResponse.StopReason.COMPLETED));
        complete.validateNext(USER);
        assertThatThrownBy(() -> complete.validateNext(results)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> complete.validateNext(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelHistory(true, List.of(exchange("first", results, List.of(), ModelResponse.StopReason.COMPLETED))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialPrefixPermitsLegacyResultsOnceWithoutPretendingTheGapIsFilled()
    {
        ModelHistory partial = new ModelHistory(false, List.of());
        partial.validateNext(results("legacy-call"));
        partial.validateNext(USER);
        ModelHistory settled = partial.append(exchange("continued", results("legacy-call"), List.of(), ModelResponse.StopReason.COMPLETED));
        assertThat(settled.complete()).isFalse();
        settled.validateNext(USER);
        assertThatThrownBy(() -> settled.validateNext(results("other"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void additiveRequestHistoryKeepsTheOldDefaultAndNativeReplayRequirements()
    {
        assertThat(ModelRequest.builder("request", "model", USER, 128).build().history()).isEmpty();
        ModelHistory prefix = new ModelHistory(true, List.of(exchange("old", USER, List.of(CALL), ModelResponse.StopReason.TOOL_CALLS)));
        ModelRequest request = ModelRequest.builder("next", "model", results("call"), 128).history(prefix)
                .replayState(new ModelReplayState("provider", 1, "private signed replay")).build();
        assertThat(request.history()).contains(prefix);
        assertThat(request.input()).isEqualTo(results("call"));
        assertThatThrownBy(() -> ModelRequest.builder("next", "model", USER, 128).history(prefix).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelRequest.builder("next", "model", results("call"), 128).history(prefix).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelRequest.builder("next", "model", USER, 128).history(null)).isInstanceOf(NullPointerException.class);
        ModelHistory completed = new ModelHistory(true, List.of(exchange("same", USER, List.of(), ModelResponse.StopReason.COMPLETED)));
        assertThatThrownBy(() -> ModelRequest.builder("same", "model", USER, 128).history(completed).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exchangeRejectsMalformedValuesAndNeverAuthorizesToolsFromAnotherStopReason()
    {
        for (String blank : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> new ModelHistory.Exchange(blank, "provider", "model", USER, "", List.of(), ModelResponse.StopReason.COMPLETED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ModelHistory.Exchange("id", blank, "model", USER, "", List.of(), ModelResponse.StopReason.COMPLETED))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ModelHistory.Exchange("id", "provider", blank, USER, "", List.of(), ModelResponse.StopReason.COMPLETED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> exchange("id", null, List.of(), ModelResponse.StopReason.COMPLETED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> exchange("id", USER, null, ModelResponse.StopReason.COMPLETED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> exchange("id", USER, List.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelHistory.Exchange("id", "provider", "model", USER, null, List.of(), ModelResponse.StopReason.COMPLETED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> exchange("id", USER, List.of(CALL, CALL), ModelResponse.StopReason.TOOL_CALLS)).isInstanceOf(IllegalArgumentException.class);
        for (ModelResponse.StopReason reason : ModelResponse.StopReason.values()) {
            if (reason == ModelResponse.StopReason.TOOL_CALLS) {
                assertThatThrownBy(() -> exchange("id", USER, List.of(), reason)).isInstanceOf(IllegalArgumentException.class);
            }
            else {
                assertThat(exchange("id", USER, List.of(), reason).stopReason()).isEqualTo(reason);
                assertThatThrownBy(() -> exchange("id", USER, List.of(CALL), reason)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    private static ModelHistory.Exchange exchange(String id, ModelInput input, List<ModelResponse.ToolCall> calls, ModelResponse.StopReason reason)
    {
        return new ModelHistory.Exchange(id, "provider", "model", input, "Private answer", calls, reason);
    }

    private static ModelInput.ToolResults results(String... ids)
    {
        return new ModelInput.ToolResults(Arrays.stream(ids).map(id -> new ModelInput.ToolResult(id, "Result", false)).toList());
    }
}

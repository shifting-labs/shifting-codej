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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompaction;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestContextReplay
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OLD = "{\"role\":\"user\",\"content\":\"old input\"},{\"role\":\"assistant\",\"content\":\"old answer\"}";
    private static final String TAIL = "{\"role\":\"user\",\"content\":\"kept\"},{\"role\":\"assistant\",\"content\":\"answer\","
            + "\"reasoning_content\":\"private reasoning\",\"signature\":\"signed-value\",\"future_field\":{\"data\":[1,true]}}";
    private static final ModelHistory HISTORY = new ModelHistory(true, List.of(exchange("one"), exchange("two")));
    private static final ModelReplayState NATIVE = new ModelReplayState("fixture", 1, "{\"messages\":[" + OLD + "," + TAIL + "]}");

    private record Summary(String role, String content) {}

    @Test
    void memoryReadersPreserveBoundaryAndRejectInvalidMarkersAndOwners()
            throws Exception
    {
        var request = new ModelCompactionRequest("model", HISTORY, NATIVE, new ModelCompaction("summary", 1), Optional.empty());
        var compacted = ContextReplay.compact(request, messages(OLD + "," + TAIL), JSON.valueToTree(new Summary("user", ContextReplay.SUMMARY_PREFIX + "summary")), exchange -> 2);
        assertThat(ContextReplay.memory(NATIVE, "fixture", node -> "unused")).isEmpty();
        var memory = ContextReplay.memory(compacted, "fixture", node -> ContextReplay.SUMMARY_PREFIX + "summary").orElseThrow();
        assertThat(memory.throughRequestId()).isEqualTo("one");
        assertThat(memory.summary()).isEqualTo("summary");
        assertThatThrownBy(() -> ContextReplay.memory(compacted, "fixture", node -> "unmarked text")).isInstanceOf(ModelClientException.class);
        assertThatThrownBy(() -> ContextReplay.memory(compacted, "fixture", node -> ContextReplay.SUMMARY_PREFIX)).isInstanceOf(ModelClientException.class);
        assertThatThrownBy(() -> ContextReplay.memory(compacted, "fixture", node -> null)).isInstanceOf(ModelClientException.class);
        assertThatThrownBy(() -> ContextReplay.memory(compacted, "foreign", node -> "unused")).isInstanceOf(ModelClientException.class);
    }

    @Test
    void compactionPreservesOpaqueNativeTailAndCarriesTheBoundaryAcrossResponses()
            throws Exception
    {
        var request = new ModelCompactionRequest("model", HISTORY, NATIVE, new ModelCompaction("summary", 1), Optional.empty());
        ModelReplayState compacted = ContextReplay.compact(request, messages(OLD + "," + TAIL), JSON.valueToTree(new Summary("user", "summary")), exchange -> 2);
        assertThat(compacted.providerId()).isEqualTo("fixture");
        assertThat(compacted.formatVersion()).isEqualTo(2);
        assertThat(ContextReplay.nativeReplay(compacted).payload()).contains(TAIL, "summary").doesNotContain("old input", "old answer");
        assertThat(NATIVE.payload()).contains(OLD, TAIL);
        ModelResponse nativeResponse = new ModelResponse("answer", List.of(), ModelResponse.StopReason.COMPLETED, ContextReplay.nativeReplay(compacted), Optional.empty());
        ModelResponse carried = ContextReplay.carry(Optional.of(compacted), nativeResponse);
        assertThat(carried.replayState().formatVersion()).isEqualTo(2);
        assertThat(ContextReplay.nativeReplay(carried.replayState()).payload()).isEqualTo(nativeResponse.replayState().payload());
        assertThat(carried.text()).isEqualTo("answer");
        assertThat(ContextReplay.carry(Optional.empty(), nativeResponse)).isSameAs(nativeResponse);
        assertThat(ContextReplay.carry(Optional.of(NATIVE), nativeResponse)).isSameAs(nativeResponse);
    }

    @Test
    void repeatedCompactionCannotRestoreRemovedNativeExchangesAndCanSummarizeTheEntirePrefix()
            throws Exception
    {
        var first = new ModelCompactionRequest("model", HISTORY, NATIVE, new ModelCompaction("one", 0), Optional.empty());
        var compacted = ContextReplay.compact(first, messages(OLD + "," + TAIL), JSON.valueToTree(new Summary("user", "one")), exchange -> 2);
        var backwards = new ModelCompactionRequest("model", HISTORY, compacted, new ModelCompaction("two", 1), Optional.empty());
        assertThatThrownBy(() -> ContextReplay.compact(backwards, messages("{\"role\":\"user\",\"content\":\"one\"}"),
                JSON.valueToTree(new Summary("user", "two")), exchange -> 2)).isInstanceOf(ModelClientException.class);
        var again = new ModelCompactionRequest("model", HISTORY, compacted, new ModelCompaction("two", 0), Optional.empty());
        var second = ContextReplay.compact(again, messages("{\"role\":\"user\",\"content\":\"one\"}"),
                JSON.valueToTree(new Summary("user", "two")), exchange -> 2);
        assertThat(ContextReplay.nativeReplay(second).payload()).isEqualTo("{\"messages\":[{\"role\":\"user\",\"content\":\"two\"}]}");
    }

    @Test
    void malformedCoercedDuplicateMissingAndForeignWrapperFieldsAreRejected()
            throws Exception
    {
        var compacted = ContextReplay.compact(new ModelCompactionRequest("model", HISTORY, NATIVE, new ModelCompaction("summary", 1), Optional.empty()),
                messages(OLD + "," + TAIL), JSON.valueToTree(new Summary("user", "summary")), exchange -> 2);
        for (String malformed : List.of("{}", "null", "{\"through\":null}", compacted.payload() + " {}",
                compacted.payload().replace("\"through\":\"one\"", "\"through\":3"),
                compacted.payload().replace("\"version\":1", "\"version\":\"1\""),
                compacted.payload().replace("\"through\":\"one\"", "\"through\":\"one\",\"through\":\"two\""),
                compacted.payload().replace("\"provider\":\"fixture\"", "\"provider\":\"foreign\""),
                compacted.payload().replace("\"version\":1", "\"version\":2"))) {
            assertThatThrownBy(() -> ContextReplay.nativeReplay(new ModelReplayState("fixture", 2, malformed)))
                    .isInstanceOf(ModelClientException.class).hasMessageNotContaining("private reasoning").hasMessageNotContaining("signed-value");
        }
        assertThatThrownBy(() -> ContextReplay.carry(Optional.of(compacted), new ModelResponse("answer", List.of(), ModelResponse.StopReason.COMPLETED,
                new ModelReplayState("foreign", 1, "{}"), Optional.empty()))).isInstanceOf(ModelClientException.class);
    }

    @Test
    void inconsistentHistoryMissingBoundariesAndExpiredRequestsCannotBeCompacted()
            throws Exception
    {
        var request = new ModelCompactionRequest("model", HISTORY, NATIVE, new ModelCompaction("summary", 1), Optional.empty());
        assertThatThrownBy(() -> ContextReplay.compact(request, messages(TAIL), JSON.valueToTree(new Summary("user", "summary")), exchange -> 2))
                .isInstanceOf(ModelClientException.class);
        var expired = new ModelCompactionRequest("model", HISTORY, NATIVE, new ModelCompaction("summary", 1), Optional.of(Instant.MIN));
        assertThatThrownBy(() -> ContextReplay.compact(expired, messages(OLD + "," + TAIL), JSON.valueToTree(new Summary("user", "summary")), exchange -> 2))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        var compacted = ContextReplay.compact(request, messages(OLD + "," + TAIL), JSON.valueToTree(new Summary("user", "summary")), exchange -> 2);
        var missing = new ModelReplayState("fixture", 2, compacted.payload().replace("\"through\":\"one\"", "\"through\":\"absent\""));
        assertThatThrownBy(() -> ContextReplay.compact(new ModelCompactionRequest("model", HISTORY, missing, request.compaction(), Optional.empty()),
                messages(TAIL), JSON.valueToTree(new Summary("user", "summary")), exchange -> 2)).isInstanceOf(ModelClientException.class);
    }

    private static ArrayNode messages(String elements)
            throws Exception
    {
        return (ArrayNode) JSON.readTree("[" + elements + "]");
    }

    private static ModelHistory.Exchange exchange(String id)
    {
        return new ModelHistory.Exchange(id, "fixture", "model", new ModelInput.UserMessage("user " + id, List.of()), "answer", List.of(), ModelResponse.StopReason.COMPLETED);
    }
}

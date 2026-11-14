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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionMemory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCompactionRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Strict typed envelope; native message blocks remain opaque and are copied without semantic rewriting. */
final class ContextReplay
{
    static final String SUMMARY_PREFIX = "[Conversation summary: context data]\n";
    private static final int MAX_CHARACTERS = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = mapper();

    private ContextReplay() {}

    private static ObjectMapper mapper()
    {
        var mapper = JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        mapper.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    private record NativeData(@JsonProperty(required = true) String provider, @JsonProperty(required = true) int version,
            @JsonProperty(required = true) String payload)
    {
        NativeData
        {
            requireNonNull(payload, "payload is null");
            checkArgument(version == 1, "unsupported native replay version");
            new ModelReplayState(provider, version, payload);
        }

        ModelReplayState state() { return new ModelReplayState(provider, version, payload); }

        static NativeData of(ModelReplayState state) { return new NativeData(state.providerId(), state.formatVersion(), state.payload()); }
    }

    private record Envelope(@JsonProperty(required = true) String through, @JsonProperty(required = true) NativeData nativeReplay)
    {
        Envelope
        {
            checkText(through, "through");
            requireNonNull(nativeReplay, "nativeReplay is null");
        }
    }

    private record Messages(@JsonProperty(required = true) List<JsonNode> messages)
    {
        Messages
        {
            messages = List.copyOf(messages).stream().map(node -> {
                checkArgument(node.isObject(), "native message must be an object");
                return node.<JsonNode>deepCopy();
            }).toList();
        }
    }

    static Optional<ModelCompactionMemory> memory(ModelReplayState state, String provider, Function<JsonNode, String> decode)
    {
        requireNonNull(state, "state is null");
        if (!state.providerId().equals(provider) || (state.formatVersion() != 1 && state.formatVersion() != 2)) {
            throw invalid("Foreign or incompatible summary memory");
        }
        if (state.formatVersion() == 1) {
            return Optional.empty();
        }
        try {
            Envelope envelope = envelope(state);
            Messages nativeMessages = JSON.readValue(envelope.nativeReplay().payload(), Messages.class);
            checkArgument(!nativeMessages.messages().isEmpty(), "summary message is absent");
            String text = requireNonNull(decode.apply(nativeMessages.messages().getFirst()), "summary text is null");
            checkArgument(text.startsWith(SUMMARY_PREFIX), "invalid summary marker");
            return Optional.of(new ModelCompactionMemory(envelope.through(), text.substring(SUMMARY_PREFIX.length())));
        }
        catch (IOException | IllegalArgumentException | NullPointerException failure) {
            throw invalid("Invalid committed summary memory");
        }
    }

    static ModelReplayState nativeReplay(ModelReplayState state)
    {
        requireNonNull(state, "state is null");
        if (state.formatVersion() != 2) {
            return state;
        }
        return envelope(state).nativeReplay().state();
    }

    private static Envelope envelope(ModelReplayState state)
    {
        try {
            checkArgument(state.formatVersion() == 2 && state.payload().length() <= MAX_CHARACTERS, "invalid replay envelope");
            Envelope envelope = JSON.readValue(state.payload(), Envelope.class);
            checkArgument(state.providerId().equals(envelope.nativeReplay().provider()), "replay owner mismatch");
            return envelope;
        }
        catch (IOException | IllegalArgumentException | NullPointerException failure) {
            throw invalid("Invalid compacted replay envelope");
        }
    }

    static ModelReplayState compact(ModelCompactionRequest request, ArrayNode validatedMessages, JsonNode summary,
            ToIntFunction<ModelHistory.Exchange> messageCount)
    {
        checkActive(request);
        var exchanges = request.history().exchanges();
        int boundary = exchanges.size() - request.compaction().retainExchanges();
        int previous = 0;
        if (request.replay().formatVersion() == 2) {
            String through = envelope(request.replay()).through();
            previous = java.util.stream.IntStream.range(0, exchanges.size()).filter(index -> exchanges.get(index).requestId().equals(through))
                    .findFirst().orElseThrow(() -> invalid("Compaction boundary is absent from history")) + 1;
        }
        if (boundary < previous) {
            throw invalid("Compaction cannot restore removed native context");
        }
        int expected = previous == 0 ? 0 : 1;
        int retained = 0;
        for (int index = previous; index < exchanges.size(); index++) {
            int count = messageCount.applyAsInt(exchanges.get(index));
            expected = Math.addExact(expected, count);
            if (index >= boundary) {
                retained = Math.addExact(retained, count);
            }
        }
        if (validatedMessages.size() != expected) {
            throw invalid("Native replay and canonical history disagree");
        }
        List<JsonNode> messages = new ArrayList<>();
        messages.add(requireNonNull(summary, "summary is null"));
        int keepFrom = validatedMessages.size() - retained;
        int index = 0;
        for (JsonNode message : validatedMessages) {
            if (index++ >= keepFrom) {
                messages.add(message);
            }
        }
        try {
            ModelReplayState nativeState = new ModelReplayState(request.replay().providerId(), 1, JSON.writeValueAsString(new Messages(messages)));
            ModelReplayState compacted = wrap(exchanges.get(boundary - 1).requestId(), nativeState);
            checkActive(request);
            return compacted;
        }
        catch (IOException failure) {
            throw invalid("Compacted replay could not be encoded");
        }
    }

    static ModelResponse carry(Optional<ModelReplayState> previous, ModelResponse response)
    {
        if (previous.isEmpty() || previous.orElseThrow().formatVersion() != 2) {
            return response;
        }
        Envelope envelope = envelope(previous.orElseThrow());
        if (!previous.orElseThrow().providerId().equals(response.replayState().providerId())) {
            throw invalid("Compacted response changed replay owner");
        }
        return new ModelResponse(response.text(), response.toolCalls(), response.stopReason(), wrap(envelope.through(), response.replayState()), response.usage());
    }

    private static ModelReplayState wrap(String through, ModelReplayState nativeState)
    {
        try {
            String payload = JSON.writeValueAsString(new Envelope(through, NativeData.of(nativeState)));
            checkArgument(payload.length() <= MAX_CHARACTERS, "compacted replay too large");
            return new ModelReplayState(nativeState.providerId(), 2, payload);
        }
        catch (IOException | IllegalArgumentException failure) {
            throw invalid("Compacted replay could not be encoded");
        }
    }

    private static ModelClientException invalid(String message)
    {
        return new ModelClientException(ModelClientException.Kind.INVALID_REQUEST, message, false);
    }

    private static void checkActive(ModelCompactionRequest request)
    {
        requireNonNull(request, "request is null");
        if (Thread.currentThread().isInterrupted() || request.deadline().filter(deadline -> !deadline.isAfter(Instant.now())).isPresent()) {
            throw new CancellationException("Context compaction cancelled or deadline exceeded");
        }
    }
}

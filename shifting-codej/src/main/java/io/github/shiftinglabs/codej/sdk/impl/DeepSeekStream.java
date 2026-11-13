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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClientException;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.github.shiftinglabs.codej.sdk.impl.DeepSeekModelClient.JSON;
import static io.github.shiftinglabs.codej.sdk.impl.DeepSeekModelClient.protocol;

/** Strict single-choice SSE assembly. Private reasoning is replayed but never sent to display callbacks. */
final class DeepSeekStream
{
    private final ModelEventSink events;
    private final Supplier<SecretRedactor> redactor;
    private final BooleanSupplier cancelled;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final List<ToolParts> tools = new ArrayList<>();
    private String held = "";
    private String id;
    private String model;
    private String finish;
    private boolean hasReasoning;
    private boolean hasReasoningText;
    private boolean hasText;
    private JsonNode usage;

    DeepSeekStream(ModelEventSink events, Supplier<SecretRedactor> redactor, BooleanSupplier cancelled)
    {
        this.events = events;
        this.redactor = redactor;
        this.cancelled = cancelled;
    }

    ModelResponse read(InputStream source, ArrayNode history)
            throws IOException
    {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new InputStreamReader(source, decoder);
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        int count = 0;
        boolean first = true;
        boolean cr = false;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            int value = reader.read();
            if (value < 0) {
                throw protocol();
            }
            if (++count > 8 * 1024 * 1024) {
                throw protocol();
            }
            if (first && value == '\ufeff') {
                first = false;
                continue;
            }
            first = false;
            if (value == '\n' && cr) {
                cr = false;
                continue;
            }
            cr = value == '\r';
            if (value != '\n' && value != '\r') {
                line.append((char) value);
                continue;
            }
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    String payload = data.substring(0, data.length() - 1);
                    data.setLength(0);
                    if (payload.equals("[DONE]")) {
                        return finish(history);
                    }
                    accept(payload);
                }
            }
            else if (line.toString().startsWith("data:")) {
                String valueText = line.substring(5);
                data.append(valueText.startsWith(" ") ? valueText.substring(1) : valueText).append('\n');
            }
            else if (!line.toString().startsWith(":") && !line.toString().startsWith("event:") && !line.toString().startsWith("id:")
                    && !line.toString().startsWith("retry:")) {
                throw protocol();
            }
            line.setLength(0);
        }
    }

    private void accept(String payload)
    {
        JsonNode chunk;
        try {
            chunk = JSON.readTree(payload);
        }
        catch (IOException failure) {
            throw protocol();
        }
        if (chunk == null || !chunk.isObject() || finish != null) {
            throw protocol();
        }
        if (chunk.has("error")) {
            throw new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "DeepSeek stream reported an error", true);
        }
        if (!chunk.path("object").asText().equals("chat.completion.chunk") || !chunk.path("id").isTextual() || chunk.path("id").asText().isBlank()
                || !chunk.path("model").isTextual() || !chunk.path("choices").isArray() || chunk.path("choices").size() != 1) {
            throw protocol();
        }
        if (id == null) {
            id = chunk.path("id").textValue();
            model = chunk.path("model").textValue();
        }
        else if (!id.equals(chunk.path("id").asText()) || !model.equals(chunk.path("model").asText())) {
            throw protocol();
        }
        JsonNode choice = chunk.path("choices").get(0);
        JsonNode delta = choice.path("delta");
        if (!choice.path("index").isIntegralNumber() || !choice.path("index").canConvertToInt() || choice.path("index").intValue() != 0 || !delta.isObject()
                || delta.properties().stream().anyMatch(entry -> !Set.of("role", "content", "reasoning_content", "tool_calls").contains(entry.getKey()))
                || delta.hasNonNull("role") && !delta.path("role").asText().equals("assistant")) {
            throw protocol();
        }
        if (delta.hasNonNull("content")) {
            if (!delta.path("content").isTextual()) {
                throw protocol();
            }
            hasText = true;
            String part = delta.path("content").textValue();
            text.append(part);
            SecretRedactor.Chunk next = redactor.get().redactChunk(held, part);
            held = next.held();
            if (!next.text().isEmpty()) {
                events.textDelta(next.text());
            }
        }
        if (delta.has("reasoning_content")) {
            hasReasoning = true;
            if (delta.hasNonNull("reasoning_content")) {
                if (!delta.path("reasoning_content").isTextual()) {
                    throw protocol();
                }
                hasReasoningText = true;
                reasoning.append(delta.path("reasoning_content").textValue());
            }
        }
        if (delta.hasNonNull("tool_calls")) {
            if (!delta.path("tool_calls").isArray()) {
                throw protocol();
            }
            delta.path("tool_calls").forEach(this::tool);
        }
        if (chunk.hasNonNull("usage")) {
            if (!chunk.path("usage").isObject()) {
                throw protocol();
            }
            usage = chunk.path("usage").deepCopy();
        }
        if (choice.hasNonNull("finish_reason")) {
            if (!choice.path("finish_reason").isTextual()) {
                throw protocol();
            }
            finish = choice.path("finish_reason").textValue();
        }
    }

    private void tool(JsonNode part)
    {
        if (!part.path("index").isIntegralNumber() || !part.path("index").canConvertToInt() || part.path("index").intValue() < 0
                || part.path("index").intValue() > tools.size() || tools.size() >= 1024
                || part.properties().stream().anyMatch(entry -> !Set.of("index", "id", "type", "function").contains(entry.getKey()))) {
            throw protocol();
        }
        int index = part.path("index").intValue();
        if (index == tools.size()) {
            if (!part.path("id").isTextual() || part.path("id").asText().isBlank() || !part.path("type").asText().equals("function")
                    || !part.path("function").path("name").isTextual() || part.path("function").path("name").asText().isBlank()) {
                throw protocol();
            }
            ObjectNode tool = JSON.createObjectNode().put("id", part.path("id").asText()).put("type", "function");
            tool.putObject("function").put("name", part.path("function").path("name").asText()).put("arguments", "");
            tools.add(new ToolParts(tool));
        }
        ToolParts pending = tools.get(index);
        ObjectNode tool = pending.value;
        JsonNode function = part.path("function");
        if (part.has("id") && !part.path("id").equals(tool.path("id")) || part.has("type") && !part.path("type").asText().equals("function")
                || !function.isObject() || function.has("name") && !function.path("name").equals(tool.path("function").path("name"))
                || function.properties().stream().anyMatch(entry -> !Set.of("name", "arguments").contains(entry.getKey()))) {
            throw protocol();
        }
        if (function.has("arguments")) {
            if (!function.path("arguments").isTextual()) {
                throw protocol();
            }
            pending.arguments.append(function.path("arguments").textValue());
        }
    }

    private ModelResponse finish(ArrayNode history)
    {
        if (finish == null) {
            throw protocol();
        }
        ModelResponse.StopReason reason = switch (finish) {
            case "stop" -> ModelResponse.StopReason.COMPLETED;
            case "tool_calls" -> ModelResponse.StopReason.TOOL_CALLS;
            case "length" -> ModelResponse.StopReason.OUTPUT_LIMIT;
            case "content_filter" -> ModelResponse.StopReason.REFUSED;
            case "insufficient_system_resource", "aborted" -> ModelResponse.StopReason.PAUSED;
            default -> throw protocol();
        };
        if ((reason == ModelResponse.StopReason.TOOL_CALLS) != !tools.isEmpty()) {
            throw protocol();
        }
        tools.forEach(tool -> tool.value.withObject("function").put("arguments", tool.arguments.toString()));
        List<ModelResponse.ToolCall> calls = tools.stream().map(tool -> tool.value).map(tool -> new ModelResponse.ToolCall(tool.path("id").asText(),
                tool.path("function").path("name").asText(), tool.path("function").path("arguments").asText())).toList();
        ObjectNode message = history.addObject().put("role", "assistant").put("content", text.toString());
        if (!hasText) {
            message.putNull("content");
        }
        if (hasReasoning) {
            if (hasReasoningText) {
                message.put("reasoning_content", reasoning.toString());
            }
            else {
                message.putNull("reasoning_content");
            }
        }
        if (!tools.isEmpty()) {
            message.set("tool_calls", JSON.valueToTree(tools.stream().map(tool -> tool.value).toList()));
        }
        ObjectNode replay = JSON.createObjectNode();
        replay.set("messages", history);
        Optional<ProviderUsageSample> sample = Optional.empty();
        if (usage != null) {
            long input = tokens(usage, "prompt_tokens");
            long output = tokens(usage, "completion_tokens");
            long cached = usage.has("prompt_cache_hit_tokens") ? tokens(usage, "prompt_cache_hit_tokens")
                    : usage.path("prompt_tokens_details").has("cached_tokens") ? tokens(usage.path("prompt_tokens_details"), "cached_tokens") : 0;
            if (cached > input || usage.path("prompt_tokens_details").has("cached_tokens") && tokens(usage.path("prompt_tokens_details"), "cached_tokens") != cached
                    || usage.has("prompt_cache_miss_tokens") && Math.addExact(tokens(usage, "prompt_cache_miss_tokens"), cached) != input
                    || usage.has("total_tokens") && tokens(usage, "total_tokens") != Math.addExact(input, output)) {
                throw protocol();
            }
            sample = Optional.of(new ProviderUsageSample(Optional.of(id), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, input, cached, output, Optional.empty()));
        }
        if (!held.isEmpty()) {
            events.textDelta(redactor.get().redact(held));
            held = "";
        }
        return new ModelResponse(redactor.get().redact(text.toString()), calls, reason, new ModelReplayState("deepseek", 1, replay.toString()), sample);
    }

    private static final class ToolParts
    {
        final ObjectNode value;
        final StringBuilder arguments = new StringBuilder();

        ToolParts(ObjectNode value) { this.value = value; }
    }

    private static long tokens(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw protocol();
        }
        return value.longValue();
    }
}

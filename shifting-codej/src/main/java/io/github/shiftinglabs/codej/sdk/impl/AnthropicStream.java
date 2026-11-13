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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.github.shiftinglabs.codej.sdk.impl.AnthropicModelClient.JSON;
import static io.github.shiftinglabs.codej.sdk.impl.AnthropicModelClient.protocol;

/** Validates the SDK's decoded event sequence and retains complete private content independently of visible text. */
final class AnthropicStream
{
    private final ModelEventSink events;
    private final SecretRedactor redactor;
    private final List<Block> blocks = new ArrayList<>();
    private final StringBuilder visible = new StringBuilder();
    private ObjectNode message;
    private String held = "";
    private boolean complete;
    private int size;

    AnthropicStream(ModelEventSink events, SecretRedactor redactor)
    {
        this.events = events;
        this.redactor = redactor;
    }

    boolean accept(JsonNode event)
    {
        size = Math.addExact(size, event.toString().length());
        if (size > 8 * 1024 * 1024 || complete) {
            throw protocol();
        }
        switch (event.path("type").asText()) {
            case "ping" -> {}
            case "message_start" -> {
                if (message != null || !event.path("message").isObject() || !event.path("message").path("content").isArray()
                        || !event.path("message").path("content").isEmpty()) {
                    throw protocol();
                }
                message = ((ObjectNode) event.path("message")).deepCopy();
            }
            case "content_block_start" -> {
                requireStarted();
                if (!event.path("index").isIntegralNumber() || !event.path("index").canConvertToInt() || event.path("index").asInt() != blocks.size() || blocks.size() >= 1024
                        || !event.path("content_block").isObject()) {
                    throw protocol();
                }
                ObjectNode data = ((ObjectNode) event.path("content_block")).deepCopy();
                String type = data.path("type").asText();
                if (!Set.of("text", "thinking", "redacted_thinking", "tool_use").contains(type)
                        || data.has("caller") && !data.path("caller").path("type").asText().equals("direct")) {
                    throw protocol();
                }
                blocks.add(new Block(data));
                if (type.equals("text")) {
                    text(data.path("text").asText());
                }
            }
            case "content_block_delta" -> {
                Block block = block(event);
                JsonNode delta = event.path("delta");
                String type = block.data.path("type").asText();
                Set<String> fields = switch (delta.path("type").asText()) {
                    case "text_delta" -> Set.of("type", "text");
                    case "thinking_delta" -> Set.of("type", "thinking");
                    case "signature_delta" -> Set.of("type", "signature");
                    case "input_json_delta" -> Set.of("type", "partial_json");
                    default -> throw protocol();
                };
                if (delta.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))) {
                    throw protocol();
                }
                switch (delta.path("type").asText()) {
                    case "text_delta" -> {
                        if (!type.equals("text") || !delta.path("text").isTextual()) {
                            throw protocol();
                        }
                        String text = delta.path("text").textValue();
                        block.data.put("text", block.data.path("text").asText() + text);
                        text(text);
                    }
                    case "thinking_delta", "signature_delta" -> {
                        String field = delta.path("type").asText().equals("thinking_delta") ? "thinking" : "signature";
                        if (!type.equals("thinking") || !delta.path(field).isTextual()) {
                            throw protocol();
                        }
                        block.data.put(field, block.data.path(field).asText() + delta.path(field).textValue());
                    }
                    case "input_json_delta" -> {
                        if (!type.equals("tool_use") || !delta.path("partial_json").isTextual()) {
                            throw protocol();
                        }
                        block.arguments.append(delta.path("partial_json").textValue());
                    }
                    default -> throw protocol();
                }
            }
            case "content_block_stop" -> {
                Block block = block(event);
                if (!block.arguments.isEmpty()) {
                    try {
                        if (!block.data.path("input").isObject() || !block.data.path("input").isEmpty()) {
                            throw protocol();
                        }
                        JsonNode input = JSON.readTree(block.arguments.toString());
                        if (!input.isObject()) {
                            throw protocol();
                        }
                        block.data.set("input", input);
                    }
                    catch (IOException failure) {
                        throw protocol();
                    }
                }
                block.closed = true;
            }
            case "message_delta" -> {
                requireStarted();
                if (!event.path("delta").isObject() || !event.path("usage").isObject()) {
                    throw protocol();
                }
                event.path("delta").properties().forEach(entry -> message.set(entry.getKey(), entry.getValue()));
                ObjectNode usage = message.withObject("usage");
                event.path("usage").properties().forEach(entry -> usage.set(entry.getKey(), entry.getValue()));
            }
            case "message_stop" -> {
                requireStarted();
                if (!message.path("stop_reason").isTextual() || blocks.stream().anyMatch(block -> !block.closed)) {
                    throw protocol();
                }
                complete = true;
            }
            case "error" -> throw new ModelClientException(ModelClientException.Kind.TEMPORARILY_UNAVAILABLE, "Claude stream reported an error", true);
            default -> throw protocol();
        }
        return complete;
    }

    private void requireStarted()
    {
        if (message == null) {
            throw protocol();
        }
    }

    private Block block(JsonNode event)
    {
        requireStarted();
        if (!event.path("index").isIntegralNumber() || !event.path("index").canConvertToInt()) {
            throw protocol();
        }
        int index = event.path("index").asInt();
        if (index < 0 || index >= blocks.size() || blocks.get(index).closed) {
            throw protocol();
        }
        return blocks.get(index);
    }

    private void text(String text)
    {
        visible.append(text);
        SecretRedactor.Chunk next = redactor.redactChunk(held, text);
        held = next.held();
        if (!next.text().isEmpty()) {
            events.textDelta(next.text());
        }
    }

    ModelResponse finish(ArrayNode history)
            throws IOException
    {
        if (!complete) {
            throw protocol();
        }
        if (!held.isEmpty()) {
            events.textDelta(redactor.redact(held));
            held = "";
        }
        ModelResponse.StopReason reason = switch (message.path("stop_reason").asText()) {
            case "end_turn", "stop_sequence" -> ModelResponse.StopReason.COMPLETED;
            case "tool_use" -> ModelResponse.StopReason.TOOL_CALLS;
            case "max_tokens", "model_context_window_exceeded" -> ModelResponse.StopReason.OUTPUT_LIMIT;
            case "refusal" -> ModelResponse.StopReason.REFUSED;
            case "pause_turn" -> ModelResponse.StopReason.PAUSED;
            default -> throw protocol();
        };
        ArrayNode content = JSON.createArrayNode();
        List<ModelResponse.ToolCall> calls = new ArrayList<>();
        for (Block block : blocks) {
            content.add(block.data);
            if (reason == ModelResponse.StopReason.TOOL_CALLS && block.data.path("type").asText().equals("tool_use")) {
                if (!block.data.path("id").isTextual() || !block.data.path("name").isTextual() || !block.data.path("input").isObject()) {
                    throw protocol();
                }
                calls.add(new ModelResponse.ToolCall(block.data.path("id").textValue(), block.data.path("name").textValue(), block.data.path("input").toString()));
            }
        }
        history.addObject().put("role", "assistant").set("content", content);
        ObjectNode replay = JSON.createObjectNode();
        replay.set("messages", history);
        JsonNode usage = message.path("usage");
        long cached = tokens(usage, "cache_read_input_tokens", false);
        long input = Math.addExact(tokens(usage, "input_tokens", true), Math.addExact(cached, tokens(usage, "cache_creation_input_tokens", false)));
        return new ModelResponse(redactor.redact(visible.toString()), calls, reason, new ModelReplayState("anthropic", 1, JSON.writeValueAsString(replay)),
                Optional.of(new ProviderUsageSample(Optional.of(message.path("id").asText()).filter(value -> !value.isBlank()),
                        ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, input, cached, tokens(usage, "output_tokens", true), Optional.empty())));
    }

    private static long tokens(JsonNode usage, String field, boolean required)
    {
        JsonNode value = usage.get(field);
        if (value == null && !required) {
            return 0;
        }
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw protocol();
        }
        return value.longValue();
    }

    private static final class Block
    {
        final ObjectNode data;
        final StringBuilder arguments = new StringBuilder();
        boolean closed;

        Block(ObjectNode data) { this.data = data; }
    }
}

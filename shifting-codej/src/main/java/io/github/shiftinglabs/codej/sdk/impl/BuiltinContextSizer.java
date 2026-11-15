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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextAssessment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextSizer;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Local text-only estimates, with an explicit catalog rather than model-prefix guesses. */
final class BuiltinContextSizer
        implements ModelContextSizer
{
    private static final ObjectMapper JSON = mapper();
    // Reviewed provider limits and estimator tradeoffs: docs/design/context-budgeting.md.
    static final ModelContextSizer CLAUDE = new BuiltinContextSizer("anthropic", Map.ofEntries(
            Map.entry("claude-fable-5-2", new Limits(1000000, 128000)),
            Map.entry("claude-opus-5-5", new Limits(1000000, 128000)),
            Map.entry("claude-sonnet-5-5", new Limits(1000000, 128000)),
            Map.entry("claude-haiku-5-5", new Limits(1000000, 128000)),
            Map.entry("claude-fable-5-1", new Limits(1000000, 128000)),
            Map.entry("claude-fable-5", new Limits(1000000, 128000)),
            Map.entry("claude-opus-5", new Limits(1000000, 128000)),
            Map.entry("claude-sonnet-5", new Limits(1000000, 128000)),
            Map.entry("claude-opus-4-8", new Limits(1000000, 128000)),
            Map.entry("claude-opus-4-7", new Limits(1000000, 128000)),
            Map.entry("claude-opus-4-6", new Limits(1000000, 128000)),
            Map.entry("claude-sonnet-4-6", new Limits(1000000, 128000)),
            Map.entry("claude-opus-4-5", new Limits(200000, 64000)),
            Map.entry("claude-opus-4-5-20251101", new Limits(200000, 64000)),
            Map.entry("claude-sonnet-4-5", new Limits(200000, 64000)),
            Map.entry("claude-sonnet-4-5-20250929", new Limits(200000, 64000)),
            Map.entry("claude-haiku-4-5", new Limits(200000, 64000)),
            Map.entry("claude-haiku-4-5-20251001", new Limits(200000, 64000))),
            serialized -> JSON.readValue(serialized, ClaudeInput.class).framing());
    static final ModelContextSizer DEEPSEEK = new BuiltinContextSizer("deepseek", Map.of(
            "deepseek-flash", new Limits(1048576, 393216),
            "deepseek-v4-pro", new Limits(1048576, 393216),
            "deepseek-v4-flash", new Limits(1048576, 393216),
            "deepseek-v4-flash-vision-exp", new Limits(1048576, 393216)),
            serialized -> JSON.readValue(serialized, DeepSeekInput.class).framing());

    private final String provider;
    private final Map<String, Limits> models;
    private final ProjectionReader reader;

    private BuiltinContextSizer(String provider, Map<String, Limits> models, ProjectionReader reader)
    {
        this.provider = requireNonNull(provider, "provider is null");
        this.models = Map.copyOf(models);
        this.reader = requireNonNull(reader, "reader is null");
    }

    @Override
    public Optional<ModelContextAssessment> assess(ModelContextInput input)
    {
        requireNonNull(input, "input is null");
        checkActive(input);
        Limits limits = models.get(input.model());
        if (!provider.equals(input.providerId()) || limits == null) {
            return Optional.empty();
        }
        try {
            long framing = reader.framing(input.serializedInput());
            checkActive(input);
            long tokens = framing + utf8Bytes(input);
            checkActive(input);
            return Optional.of(new ModelContextAssessment(limits.window(), tokens, limits.output(), ModelContextAssessment.Accuracy.ESTIMATED));
        }
        catch (IOException | IllegalArgumentException | NullPointerException unsupported) {
            // Unknown fields/modalities stay unknown; never expose parser diagnostics containing private input.
            return Optional.empty();
        }
    }

    private static ObjectMapper mapper()
    {
        var mapper = JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        mapper.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    private static long utf8Bytes(ModelContextInput input)
    {
        String text = input.serializedInput();
        long bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            if ((index & 4095) == 0) {
                checkActive(input);
            }
            char value = text.charAt(index);
            if (value <= 0x7F) {
                bytes++;
            }
            else if (value <= 0x7FF) {
                bytes += 2;
            }
            else if (Character.isSurrogate(value)) {
                checkArgument(Character.isHighSurrogate(value) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)), "invalid Unicode input");
                index++;
                bytes += 4;
            }
            else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static void checkActive(ModelContextInput input)
    {
        if (Thread.currentThread().isInterrupted() || input.deadline().filter(deadline -> !deadline.isAfter(Instant.now())).isPresent()) {
            throw new CancellationException("Context sizing cancelled or deadline exceeded");
        }
    }

    private record Limits(long window, int output)
    {
        Limits
        {
            checkArgument(window > 0 && output > 0 && output <= window, "invalid model limits");
        }
    }

    @FunctionalInterface
    private interface ProjectionReader
    {
        long framing(String serialized)
                throws IOException;
    }

    private record ClaudeInput(List<ClaudeMessage> messages, String system, List<ClaudeTool> tools)
    {
        ClaudeInput
        {
            messages = List.copyOf(messages);
            checkArgument(!messages.isEmpty(), "messages are empty");
            requireNonNull(system, "system is null");
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        long framing()
        {
            return 1024L + 64L * messages.size() + 256L * tools.size()
                    + 32L * messages.stream().mapToLong(message -> message.content().size()).sum();
        }
    }

    private record ClaudeMessage(String role, List<ClaudeBlock> content)
    {
        ClaudeMessage
        {
            checkArgument(Set.of("user", "assistant").contains(role), "unsupported role");
            content = List.copyOf(content);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ClaudeText.class, name = "text"),
            @JsonSubTypes.Type(value = ClaudeThinking.class, name = "thinking"),
            @JsonSubTypes.Type(value = ClaudeRedactedThinking.class, name = "redacted_thinking"),
            @JsonSubTypes.Type(value = ClaudeToolUse.class, name = "tool_use"),
            @JsonSubTypes.Type(value = ClaudeToolResult.class, name = "tool_result")})
    private sealed interface ClaudeBlock
            permits ClaudeText, ClaudeThinking, ClaudeRedactedThinking, ClaudeToolUse, ClaudeToolResult {}

    private record ClaudeText(String text)
            implements ClaudeBlock
    {
        ClaudeText { requireNonNull(text, "text is null"); }
    }

    private record ClaudeThinking(String thinking, String signature)
            implements ClaudeBlock
    {
        ClaudeThinking
        {
            requireNonNull(thinking, "thinking is null");
            requireNonNull(signature, "signature is null");
        }
    }

    private record ClaudeRedactedThinking(String data)
            implements ClaudeBlock
    {
        ClaudeRedactedThinking { requireNonNull(data, "data is null"); }
    }

    private record ClaudeToolUse(String id, String name, JsonNode input)
            implements ClaudeBlock
    {
        ClaudeToolUse
        {
            requireNonNull(id, "id is null");
            requireNonNull(name, "name is null");
            checkArgument(requireNonNull(input, "input is null").isObject(), "invalid tool arguments");
        }
    }

    private record ClaudeToolResult(String tool_use_id, String content, Boolean is_error)
            implements ClaudeBlock
    {
        ClaudeToolResult
        {
            requireNonNull(tool_use_id, "tool_use_id is null");
            requireNonNull(content, "content is null");
        }
    }

    private record ClaudeTool(String name, String description, JsonNode input_schema)
    {
        ClaudeTool
        {
            requireNonNull(name, "name is null");
            requireNonNull(description, "description is null");
            checkArgument(requireNonNull(input_schema, "schema is null").isObject(), "invalid schema");
        }
    }

    private record DeepSeekInput(List<DeepSeekMessage> messages, List<DeepSeekTool> tools, String reasoning_effort)
    {
        DeepSeekInput
        {
            messages = List.copyOf(messages);
            checkArgument(!messages.isEmpty(), "messages are empty");
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        long framing() { return 1024L + 64L * messages.size() + 256L * tools.size(); }
    }

    private record DeepSeekMessage(String role, String content, String reasoning_content, List<DeepSeekCall> tool_calls, String tool_call_id)
    {
        DeepSeekMessage
        {
            checkArgument(Set.of("system", "user", "assistant", "tool").contains(role), "unsupported role");
            tool_calls = tool_calls == null ? List.of() : List.copyOf(tool_calls);
            if (!role.equals("assistant")) {
                requireNonNull(content, "content is null");
                checkArgument(tool_calls.isEmpty() && reasoning_content == null, "invalid message fields");
            }
            if (role.equals("tool")) {
                requireNonNull(tool_call_id, "tool_call_id is null");
            }
        }
    }

    private record DeepSeekCall(String id, String type, DeepSeekFunction function)
    {
        DeepSeekCall
        {
            requireNonNull(id, "id is null");
            checkArgument("function".equals(type), "unsupported tool call");
            requireNonNull(function, "function is null");
        }
    }

    private record DeepSeekFunction(String name, String arguments)
    {
        DeepSeekFunction
        {
            requireNonNull(name, "name is null");
            requireNonNull(arguments, "arguments are null");
        }
    }

    private record DeepSeekTool(String type, DeepSeekDefinition function)
    {
        DeepSeekTool
        {
            checkArgument("function".equals(type), "unsupported tool");
            requireNonNull(function, "function is null");
        }
    }

    private record DeepSeekDefinition(String name, String description, JsonNode parameters)
    {
        DeepSeekDefinition
        {
            requireNonNull(name, "name is null");
            requireNonNull(description, "description is null");
            checkArgument(requireNonNull(parameters, "parameters are null").isObject(), "invalid schema");
        }
    }
}

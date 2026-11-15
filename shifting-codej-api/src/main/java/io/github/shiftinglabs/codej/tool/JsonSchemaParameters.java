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
package io.github.shiftinglabs.codej.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Cached validator for the API engine's established JSON Schema subset. Retains numeric/mixed
 * enums, schema-valued additionalProperties and existing transport limits. Domain binding is
 * supplied as an interface-backed function; tree interpretation stays inside this codec.
 *
 * @param <P> validated immutable parameter type
 */
public final class JsonSchemaParameters<P>
        implements ToolParameters<P>
{
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties", "items", "enum", "description", "title");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean", "null");
    private final JsonNode compiled;
    private final ToolSchema schema;
    private final Function<ToolArguments, P> decoder;

    private JsonSchemaParameters(String schema, Function<ToolArguments, P> decoder)
    {
        compiled = parse(requireNonNull(schema, "schema is null"));
        checkSchema(compiled, 0);
        this.schema = ToolSchema.of(schema, value -> value);
        this.decoder = requireNonNull(decoder, "decoder is null");
    }

    /**
     * Compiles a schema once and validates every call before invoking the supplied binder.
     *
     * @param <P> parameter type
     * @param schema supported schema object; spelling is preserved
     * @param decoder domain binder, invoked only after schema validation
     * @return the parameter codec
     */
    public static <P> JsonSchemaParameters<P> of(String schema, Function<ToolArguments, P> decoder)
    {
        return new JsonSchemaParameters<>(schema, decoder);
    }

    @Override
    public ToolSchema schema() { return schema; }

    @Override
    public P decode(ToolArguments arguments)
    {
        requireNonNull(arguments, "arguments is null");
        if (!accepts(compiled, parse(arguments.json()))) {
            throw new IllegalArgumentException("Tool arguments do not match the advertised schema");
        }
        return requireNonNull(decoder.apply(arguments), "decoder returned null");
    }

    private static JsonNode parse(String text)
    {
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Tool schema or arguments must be a JSON object");
            }
            return node;
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Tool schema or arguments must be a JSON object");
        }
    }

    private static void checkSchema(JsonNode schema, int depth)
    {
        if (depth > 32 || !schema.isObject()) {
            throw new IllegalArgumentException("Unsupported tool schema");
        }
        schema.fieldNames().forEachRemaining(key -> {
            if (!KEYWORDS.contains(key)) {
                throw new IllegalArgumentException("Unsupported tool schema keyword");
            }
        });
        if (schema.has("type") && (!schema.get("type").isTextual() || !TYPES.contains(schema.get("type").textValue()))) {
            throw new IllegalArgumentException("Unsupported tool schema type");
        }
        if (schema.has("properties")) {
            if (!schema.get("properties").isObject()) {
                throw new IllegalArgumentException("Invalid tool properties");
            }
            schema.get("properties").forEach(child -> checkSchema(child, depth + 1));
        }
        if (schema.has("items")) {
            checkSchema(schema.get("items"), depth + 1);
        }
        if (schema.has("required")) {
            if (!schema.get("required").isArray()) {
                throw new IllegalArgumentException("Invalid tool required fields");
            }
            schema.get("required").forEach(value -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("Invalid tool required field");
                }
            });
        }
        if (schema.has("additionalProperties") && !schema.get("additionalProperties").isBoolean()) {
            checkSchema(schema.get("additionalProperties"), depth + 1);
        }
        if (schema.has("enum") && (!schema.get("enum").isArray() || schema.get("enum").isEmpty())) {
            throw new IllegalArgumentException("Invalid tool enum");
        }
    }

    private static boolean accepts(JsonNode schema, JsonNode value)
    {
        if (schema.has("type")) {
            boolean type = switch (schema.get("type").textValue()) {
                case "object" -> value.isObject();
                case "array" -> value.isArray();
                case "string" -> value.isTextual();
                case "integer" -> value.isIntegralNumber();
                case "number" -> value.isNumber();
                case "boolean" -> value.isBoolean();
                case "null" -> value.isNull();
                default -> false;
            };
            if (!type) {
                return false;
            }
        }
        if (schema.has("enum")) {
            boolean match = false;
            for (JsonNode entry : schema.get("enum")) {
                match |= entry.equals(value);
            }
            if (!match) {
                return false;
            }
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.textValue())) {
                    return false;
                }
            }
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode child = schema.path("properties").get(field.getKey());
                if (child == null) {
                    JsonNode additional = schema.get("additionalProperties");
                    if (additional != null && (additional.isBoolean() ? !additional.booleanValue() : !accepts(additional, field.getValue()))) {
                        return false;
                    }
                }
                else if (!accepts(child, field.getValue())) {
                    return false;
                }
            }
        }
        if (value.isArray() && schema.has("items")) {
            for (JsonNode item : value) {
                if (!accepts(schema.get("items"), item)) {
                    return false;
                }
            }
        }
        return true;
    }
}

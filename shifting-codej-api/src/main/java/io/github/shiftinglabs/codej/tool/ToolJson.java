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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** JSON processing is confined to this boundary and builds a validated typed schema document. */
final class ToolJson
{
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(65536).maxNumberLength(256).build()).build();
    private static final ObjectMapper JSON = JsonMapper.builder(FACTORY).addModule(new Jdk8Module())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private ToolJson() {}

    static void checkObject(String json)
    {
        checkArgument(json.length() <= 65536 && StandardCharsets.UTF_8.newEncoder().canEncode(json)
                && json.getBytes(StandardCharsets.UTF_8).length <= 65536, "JSON exceeds its limit or contains invalid Unicode");
        try (var parser = FACTORY.createParser(json)) {
            checkArgument(parser.nextToken() == JsonToken.START_OBJECT, "expected one JSON object");
            parser.skipChildren();
            checkArgument(parser.nextToken() == null, "trailing JSON content");
        }
        catch (IOException failure) {
            throw new IllegalArgumentException("Invalid JSON object");
        }
    }

    static String canonicalSchema(String json)
    {
        checkObject(json);
        try (var parser = FACTORY.createParser(json)) {
            parser.nextToken();
            SchemaDocument document = schema(parser);
            checkArgument(document.type().isEmpty() || document.type().orElseThrow().equals("object"), "tool parameter schema must describe an object");
            return JSON.writeValueAsString(document);
        }
        catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid or unsupported tool schema");
        }
    }

    private static SchemaDocument schema(JsonParser parser)
            throws IOException
    {
        checkArgument(parser.currentToken() == JsonToken.START_OBJECT, "expected schema object");
        Optional<String> type = Optional.empty();
        Map<String, SchemaDocument> properties = Map.of();
        List<String> required = List.of();
        Optional<SchemaDocument> items = Optional.empty();
        Optional<List<String>> enumValues = Optional.empty();
        Optional<Boolean> additionalProperties = Optional.empty();
        Optional<String> description = Optional.empty();
        Optional<String> title = Optional.empty();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            checkArgument(parser.currentToken() == JsonToken.FIELD_NAME, "expected schema field");
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "type" -> type = Optional.of(text(parser));
                case "properties" -> properties = properties(parser);
                case "required" -> required = strings(parser);
                case "items" -> items = Optional.of(schema(parser));
                case "enum" -> enumValues = Optional.of(strings(parser));
                case "additionalProperties" -> {
                    checkArgument(parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE, "expected boolean");
                    additionalProperties = Optional.of(parser.currentToken() == JsonToken.VALUE_TRUE);
                }
                case "description" -> description = Optional.of(text(parser));
                case "title" -> title = Optional.of(text(parser));
                default -> throw new IllegalArgumentException("unsupported schema keyword");
            }
        }
        return new SchemaDocument(type, properties, required, items, enumValues, additionalProperties, description, title);
    }

    private static Map<String, SchemaDocument> properties(JsonParser parser)
            throws IOException
    {
        checkArgument(parser.currentToken() == JsonToken.START_OBJECT, "expected properties object");
        Map<String, SchemaDocument> properties = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            checkArgument(parser.currentToken() == JsonToken.FIELD_NAME, "expected property name");
            String name = parser.currentName();
            checkArgument(StandardCharsets.UTF_8.newEncoder().canEncode(name), "invalid property name");
            parser.nextToken();
            properties.put(name, schema(parser));
        }
        return properties;
    }

    private static List<String> strings(JsonParser parser)
            throws IOException
    {
        checkArgument(parser.currentToken() == JsonToken.START_ARRAY, "expected string array");
        List<String> values = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            values.add(text(parser));
        }
        return values;
    }

    private static String text(JsonParser parser)
            throws IOException
    {
        checkArgument(parser.currentToken() == JsonToken.VALUE_STRING, "expected schema string");
        String value = parser.getText();
        checkArgument(StandardCharsets.UTF_8.newEncoder().canEncode(value), "invalid schema Unicode");
        return value;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private record SchemaDocument(Optional<String> type, Map<String, SchemaDocument> properties, List<String> required,
            Optional<SchemaDocument> items, @JsonProperty("enum") Optional<List<String>> enumValues,
            Optional<Boolean> additionalProperties, Optional<String> description, Optional<String> title)
    {
        SchemaDocument
        {
            requireNonNull(type, "type is null").ifPresent(value -> checkArgument(
                    Set.of("object", "array", "string", "integer", "number", "boolean", "null").contains(value), "unsupported schema type"));
            properties = Map.copyOf(properties);
            required = required.stream().sorted().toList();
            checkArgument(Set.copyOf(required).size() == required.size(), "duplicate required field");
            requireNonNull(items, "items is null");
            enumValues = requireNonNull(enumValues, "enum is null").map(values -> {
                checkArgument(!values.isEmpty() && Set.copyOf(values).size() == values.size(), "invalid enum");
                return values.stream().sorted().toList();
            });
            requireNonNull(additionalProperties, "additionalProperties is null");
            requireNonNull(description, "description is null");
            requireNonNull(title, "title is null");
        }
    }
}

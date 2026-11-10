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
package io.github.shiftinglabs.codej.cli.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import io.github.shiftinglabs.codej.Tool;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** A fixed semantic query in the owning worktree, shared with its IDE language providers. */
public record CodeQueryBridge(Function<CodeQuery, String> query, String instruction)
{
    public static final String TOOL_NAME = "code_query";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ARGUMENTS = ImmutableSet.of("operation", "path", "query", "line", "column", "limit");
    private static final String SCHEMA = schema();

    public CodeQueryBridge
    {
        requireNonNull(query, "query is null");
        checkText(instruction, "instruction");
    }

    /** The query as a session tool; subagents may call it, since it only reads. */
    public Tool tool()
    {
        return Tool.withSchema(TOOL_NAME, "Query this working copy's IDE language services for symbols, definitions, references, implementations, calls or diagnostics. "
                + "Use a relative source path; positions are one-based UTF-16. Results may be partial while importing. Verify current source.",
                SCHEMA, arguments -> query.apply(parse(arguments))).forSubagents();
    }

    private static String schema()
    {
        try {
            return JSON.writeValueAsString(ToolSchemas.object(ImmutableMap.of(
                    "operation", ImmutableMap.of("type", "string", "enum", Arrays.stream(CodeQuery.Operation.values()).map(Enum::name).collect(ImmutableList.toImmutableList())),
                    "path", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 4096),
                    "query", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 200),
                    "line", ImmutableMap.of("type", "integer", "minimum", 1),
                    "column", ImmutableMap.of("type", "integer", "minimum", 1),
                    "limit", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", 200)), "operation", "path", "limit"));
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static CodeQuery parse(String arguments)
            throws JsonProcessingException
    {
        JsonNode call = JSON.readTree(arguments);
        checkArgument(call != null && call.isObject() && call.path("operation").isTextual() && call.path("path").isTextual(), "operation and path are required");
        checkArgument(Streams.stream(call.fieldNames()).allMatch(ARGUMENTS::contains), "Unexpected arguments");
        for (String key : ImmutableList.of("line", "column", "limit")) {
            checkArgument(!(call.has(key) || key.equals("limit")) || call.path(key).isIntegralNumber() && call.path(key).canConvertToInt(), "%s must be an integer", key);
        }
        checkArgument(!call.has("query") || call.path("query").isTextual(), "query must be text");
        return new CodeQuery(CodeQuery.Operation.valueOf(call.path("operation").textValue()), call.path("path").textValue(),
                Optional.ofNullable(call.get("query")).map(JsonNode::textValue),
                call.has("line") ? OptionalInt.of(call.path("line").intValue()) : OptionalInt.empty(),
                call.has("column") ? OptionalInt.of(call.path("column").intValue()) : OptionalInt.empty(), call.path("limit").intValue());
    }
}

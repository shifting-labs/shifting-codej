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
package io.github.shiftinglabs.codej;

import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A tool of your own that a session's agent can call, such as a lookup in your issue tracker.
 *
 * <pre>{@code
 * Tool readIssue = Tool.withSchema("read_issue", "Reads one issue by its key",
 *         """
 *         {"type": "object", "properties": {"key": {"type": "string"}}, "required": ["key"]}
 *         """,
 *         arguments -> tracker.text(arguments));
 * }</pre>
 *
 * <p>The agent sees the tool's name, description and argument schema. A tool is immutable and can be
 * given to many sessions.
 */
public final class Tool
{
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private final String name;
    private final String description;
    private final String inputSchema;
    private final Handler<String> handler;
    private final boolean subagents;

    private Tool(String name, String description, String inputSchema, Handler<String> handler, boolean subagents)
    {
        this.name = requireNonNull(name, "name is null");
        this.description = requireNonNull(description, "description is null");
        this.inputSchema = requireNonNull(inputSchema, "inputSchema is null");
        this.handler = requireNonNull(handler, "handler is null");
        this.subagents = subagents;
        checkArgument(NAME.matcher(name).matches(), "Tool names are lowercase letters, digits and underscores, starting with a letter, at most 64: %s", name);
        checkArgument(!description.isBlank(), "description is blank");
        checkArgument(!inputSchema.isBlank(), "inputSchema is blank");
    }

    /**
     * A tool whose arguments are a record. The record's components become the JSON schema the agent
     * sees, and each call's arguments bind into a new record. A component may be a {@code String},
     * {@code boolean}, {@code int}, {@code long}, {@code double}, an enum, another such record, a
     * {@code List} of any of these, or an {@code Optional} of any of these, which makes it optional.
     * A call with a missing, extra or mistyped argument fails with a message the agent can fix it from.
     *
     * <pre>{@code
     * record IssueKey(String key) {}
     *
     * Tool readIssue = Tool.of("read_issue", "Reads one issue by its key, such as ENG-1", IssueKey.class,
     *         arguments -> tracker.text(arguments.key()));
     * }</pre>
     *
     * @param <A> the arguments' record type
     * @param name what the agent calls it: lowercase letters, digits and underscores, starting with a
     *        letter, at most 64
     * @param description what the tool does and when to use it, for the agent to read
     * @param arguments the arguments' record class
     * @param handler answers each call
     * @return the tool
     * @throws IllegalArgumentException when the name is invalid, the description is blank, or the record
     *         has a component of another type
     */
    public static <A extends Record> Tool of(String name, String description, Class<A> arguments, Handler<A> handler)
    {
        requireNonNull(arguments, "arguments is null");
        requireNonNull(handler, "handler is null");
        return new Tool(name, description, RecordArguments.schema(arguments), json -> handler.call(RecordArguments.bind(json, arguments)), false);
    }

    /**
     * A tool whose arguments your own JSON schema describes.
     *
     * @param name what the agent calls it: lowercase letters, digits and underscores, starting with a
     *        letter, at most 64
     * @param description what the tool does and when to use it, for the agent to read
     * @param jsonSchema the arguments' JSON schema
     * @param handler answers each call, receiving its arguments as JSON text
     * @return the tool
     * @throws IllegalArgumentException when the name is invalid, or the description or schema is blank
     */
    public static Tool withSchema(String name, String description, String jsonSchema, Handler<String> handler)
    {
        return new Tool(name, description, jsonSchema, handler, false);
    }

    /**
     * This tool, callable by subagents as well. By default only the session's root agent can call a
     * tool.
     *
     * @return a new tool
     */
    public Tool forSubagents()
    {
        return new Tool(name, description, inputSchema, handler, true);
    }

    /**
     * Calls this tool the way the agent does, which also lets you test it.
     *
     * @param arguments the call's arguments, as JSON
     * @return the tool's result, as text for the agent
     * @throws Exception when the handler fails
     */
    public String call(String arguments)
            throws Exception
    {
        return handler.call(requireNonNull(arguments, "arguments is null"));
    }

    /**
     * What the agent calls the tool.
     *
     * @return the tool's name
     */
    public String name()
    {
        return name;
    }

    /**
     * What the agent reads about the tool.
     *
     * @return the tool's description
     */
    public String description()
    {
        return description;
    }

    /**
     * The JSON schema of the tool's arguments, as the agent sees it.
     *
     * @return the schema
     */
    public String inputSchema()
    {
        return inputSchema;
    }

    /**
     * Whether subagents can call the tool, too.
     *
     * @return {@code true} after {@link #forSubagents()}
     */
    public boolean subagents()
    {
        return subagents;
    }

    /**
     * Answers a tool's calls.
     *
     * <p>A handler is not told which agent called. A provider's shared tool endpoint cannot prove
     * whether the root or a subagent made a call, so the library enforces root-only tools by how it
     * serves them, never by asking the handler to check.
     *
     * @param <A> the type the call's arguments arrive as
     */
    @FunctionalInterface
    public interface Handler<A>
    {
        /**
         * Answers one call. Runs on the agent's callback executor and may block.
         *
         * @param arguments the call's arguments
         * @return the tool's result, as text for the agent
         * @throws Exception when the call fails; the agent receives the exception's message as a failed
         *         call, and the turn goes on
         */
        String call(A arguments)
                throws Exception;
    }
}

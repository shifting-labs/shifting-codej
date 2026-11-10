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
package io.github.shiftinglabs.codej.cli.mcp;

import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The MCP server through which models reach the host application's tools. Its name is part of every name a model sees:
 * {@code mcp__<name>__<tool>} in Claude, the {@code <name>_call_tool} catalog tools, and the Codex gateway provider.
 *
 * @param name lowercase letters and digits, single underscores between them, at most 32 characters
 */
public record ToolServer(String name)
{
    // Initialized first: DEFAULT is checked against it.
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");
    public static final ToolServer DEFAULT = new ToolServer("codej");

    public ToolServer
    {
        requireNonNull(name, "name is null");
        checkArgument(name.length() <= 32 && NAME.matcher(name).matches(),
                "tool server name must be lowercase letters and digits with single underscores, at most 32 characters: %s", name);
    }

    /** The prefix of this server's tools in Claude's tool names. */
    public String prefix()
    {
        return "mcp__" + name + "__";
    }

    public String qualified(String tool)
    {
        return prefix() + tool;
    }

    public String listTools()
    {
        return name + "_list_tools";
    }

    public String getTool()
    {
        return name + "_get_tool";
    }

    public String callTool()
    {
        return name + "_call_tool";
    }

    /** The Codex model provider a gateway session runs under. */
    public String gateway()
    {
        return name + "_gateway";
    }
}

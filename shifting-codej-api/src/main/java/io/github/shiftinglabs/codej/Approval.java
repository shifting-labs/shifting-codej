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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A request to do something beyond the session's {@link Access}, sent to its {@link ApprovalHandler}.
 *
 * <p>This set changes only in a major release; {@link Other} carries any request the library does not
 * model yet.
 */
public sealed interface Approval
{
    /**
     * The agent that asks.
     *
     * @return the root agent or a subagent
     */
    Source source();

    /**
     * Why the agent asks.
     *
     * @return the reason, in the provider's words
     */
    String reason();

    /**
     * The decisions the provider accepts for this request.
     *
     * @return the accepted decisions; always contains {@link Decision.Kind#DENY}
     */
    Set<Decision.Kind> choices();

    /**
     * Run a shell command.
     *
     * @param source the agent that asks
     * @param reason why it asks
     * @param choices the decisions the provider accepts
     * @param command the command line
     * @param workingDirectory where it would run
     * @param sandboxed whether it would run inside the sandbox; outside it, it has the user's file and network access
     * @param hosts the hosts it would reach, when the provider names them
     */
    record Command(Source source, String reason, Set<Decision.Kind> choices, String command, Path workingDirectory, boolean sandboxed, List<String> hosts)
            implements Approval
    {
        /**
         * Creates the request; copies its collections.
         *
         * @throws IllegalArgumentException when {@code choices} lacks {@link Decision.Kind#DENY}
         */
        public Command
        {
            choices = copyChoices(choices);
            hosts = ImmutableList.copyOf(hosts);
        }
    }

    /**
     * Change files outside what the session's access allows.
     *
     * @param source the agent that asks
     * @param reason why it asks
     * @param choices the decisions the provider accepts
     * @param paths the files it would change
     */
    record FileChange(Source source, String reason, Set<Decision.Kind> choices, List<Path> paths)
            implements Approval
    {
        /**
         * Creates the request; copies its collections.
         *
         * @throws IllegalArgumentException when {@code choices} lacks {@link Decision.Kind#DENY}
         */
        public FileChange
        {
            choices = copyChoices(choices);
            paths = ImmutableList.copyOf(paths);
        }
    }

    /**
     * Call a tool that needs approval, such as a provider's own MCP tool.
     *
     * @param source the agent that asks
     * @param reason why it asks
     * @param choices the decisions the provider accepts
     * @param tool the tool's name
     * @param arguments the call's arguments, as JSON
     */
    record ToolUse(Source source, String reason, Set<Decision.Kind> choices, String tool, String arguments)
            implements Approval
    {
        /**
         * Creates the request; copies {@code choices}.
         *
         * @throws IllegalArgumentException when {@code choices} lacks {@link Decision.Kind#DENY}
         */
        public ToolUse
        {
            choices = copyChoices(choices);
        }
    }

    /**
     * Any other request, such as wider sandbox permissions.
     *
     * @param source the agent that asks
     * @param reason why it asks
     * @param choices the decisions the provider accepts
     * @param detail what the provider asks for, to show the user
     */
    record Other(Source source, String reason, Set<Decision.Kind> choices, String detail)
            implements Approval
    {
        /**
         * Creates the request; copies {@code choices}.
         *
         * @throws IllegalArgumentException when {@code choices} lacks {@link Decision.Kind#DENY}
         */
        public Other
        {
            choices = copyChoices(choices);
        }
    }

    private static Set<Decision.Kind> copyChoices(Set<Decision.Kind> choices)
    {
        Set<Decision.Kind> copy = ImmutableSet.copyOf(choices);
        checkArgument(copy.contains(Decision.Kind.DENY), "choices does not contain DENY: %s", copy);
        return copy;
    }
}

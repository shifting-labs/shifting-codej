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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Which agent produced an event or made a request: the session's root agent, or a subagent it
 * started.
 *
 * @param agentId the subagent's id; empty for the root agent
 * @param parentId the id of the subagent that started this one; empty when the root agent started it
 * @param name the subagent's name or role, when the provider gives one
 */
public record Source(Optional<String> agentId, Optional<String> parentId, Optional<String> name)
{
    private static final Source ROOT = new Source(Optional.empty(), Optional.empty(), Optional.empty());

    /** Creates a source. */
    public Source
    {
        requireNonNull(agentId, "agentId is null");
        requireNonNull(parentId, "parentId is null");
        requireNonNull(name, "name is null");
    }

    /**
     * The session's root agent.
     *
     * @return the root source
     */
    public static Source root()
    {
        return ROOT;
    }

    /**
     * Whether this is the session's root agent.
     *
     * @return {@code true} when both {@link #agentId()} and {@link #parentId()} are empty; a subagent the provider names only by
     *         the agent that started it is not the root
     */
    public boolean isRoot()
    {
        return agentId.isEmpty() && parentId.isEmpty();
    }
}

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

import com.google.common.collect.ImmutableSet;

import java.util.Set;

/**
 * What an agent's CLI supports.
 *
 * @param steering whether {@link Turn#steer(Input)} can add input to a running turn
 * @param subagents whether the agent can start subagents, whose events carry their own {@link Source}
 * @param attachmentTypes the media types {@link Input#withAttachment} accepts, such as {@code image/png}
 */
public record Capabilities(boolean steering, boolean subagents, Set<String> attachmentTypes)
{
    /** Creates capabilities; copies {@code attachmentTypes}. */
    public Capabilities
    {
        attachmentTypes = ImmutableSet.copyOf(attachmentTypes);
    }
}

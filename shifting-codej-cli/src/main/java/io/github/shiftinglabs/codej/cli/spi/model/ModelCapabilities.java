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
package io.github.shiftinglabs.codej.cli.spi.model;

import java.util.Set;

import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;

/** Model features, separate from the agent engine's permissions and sandbox capabilities. */
public final class ModelCapabilities
{
    private final boolean toolCalls;
    private final Set<String> attachmentMediaTypes;
    private final Set<String> efforts;
    private final Set<String> serviceTiers;

    public ModelCapabilities(boolean toolCalls, Set<String> attachmentMediaTypes, Set<String> efforts, Set<String> serviceTiers)
    {
        this.toolCalls = toolCalls;
        this.attachmentMediaTypes = Set.copyOf(attachmentMediaTypes);
        this.efforts = Set.copyOf(efforts);
        this.serviceTiers = Set.copyOf(serviceTiers);
        this.attachmentMediaTypes.forEach(value -> checkText(value, "attachment media type"));
        this.efforts.forEach(value -> checkText(value, "effort"));
        this.serviceTiers.forEach(value -> checkText(value, "service tier"));
    }

    public static ModelCapabilities textOnly()
    {
        return new ModelCapabilities(false, Set.of(), Set.of(), Set.of());
    }

    public boolean toolCalls() { return toolCalls; }

    public Set<String> attachmentMediaTypes() { return attachmentMediaTypes; }

    public Set<String> efforts() { return efforts; }

    public Set<String> serviceTiers() { return serviceTiers; }
}

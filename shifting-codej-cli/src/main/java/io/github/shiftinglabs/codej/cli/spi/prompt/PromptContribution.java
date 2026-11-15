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
package io.github.shiftinglabs.codej.cli.spi.prompt;

import java.util.HashSet;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/** Ordered trusted instructions and user-role contextual data; the engine applies the aggregate budget too. */
public record PromptContribution(List<PromptSection> instructions, List<PromptSection> context)
{
    public static final int MAX_SECTIONS = 64;
    public static final int MAX_CHARACTERS = 65536;

    public PromptContribution
    {
        instructions = List.copyOf(instructions);
        context = List.copyOf(context);
        checkArgument((long) instructions.size() + context.size() <= MAX_SECTIONS, "too many prompt sections");
        var ids = new HashSet<String>();
        long characters = 0;
        for (List<PromptSection> group : List.of(instructions, context)) {
            for (PromptSection section : group) {
                checkArgument(ids.add(section.id()), "duplicate prompt section id");
                characters += section.text().length();
            }
        }
        checkArgument(characters <= MAX_CHARACTERS, "prompt contribution exceeds its character budget");
    }

    public static PromptContribution empty() { return new PromptContribution(List.of(), List.of()); }

    @Override
    public String toString() { return "PromptContribution[instructions=" + instructions.size() + ", context=" + context.size() + ", text redacted]"; }
}

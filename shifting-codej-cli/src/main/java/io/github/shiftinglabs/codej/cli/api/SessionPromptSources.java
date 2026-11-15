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
package io.github.shiftinglabs.codej.cli.api;

import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContext;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContribution;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContributor;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProviderBinding;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptSection;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptSessionContext;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Session-owned sources; capture is local until every contributor and aggregate bound succeeds. */
final class SessionPromptSources
{
    private record Source(String id, PromptContributor contributor) {}

    private final List<Source> owned = new ArrayList<>();

    void open(List<PromptProviderBinding> bindings, PromptSessionContext context)
    {
        for (PromptProviderBinding binding : bindings) {
            PromptContributor contributor = binding.open(context);
            owned.add(new Source(binding.id(), contributor));
        }
    }

    ApiPromptSnapshot capture(String base, PromptContext context, int maximumTextChars, Runnable checkStop)
    {
        requireNonNull(base, "base instructions are null");
        var instructions = new StringBuilder();
        var data = new StringBuilder();
        int sections = 0;
        for (Source source : owned) {
            checkStop.run();
            PromptContribution contribution = requireNonNull(source.contributor().contribute(context), "contributor returned no prompt contribution");
            checkStop.run();
            sections = Math.addExact(sections, contribution.instructions().size() + contribution.context().size());
            checkArgument(sections <= PromptContribution.MAX_SECTIONS, "too many contributed prompt sections");
            append(instructions, data, source.id(), contribution.instructions(), true);
            append(instructions, data, source.id(), contribution.context(), false);
        }
        checkArgument(data.length() <= maximumTextChars - context.input().text().length(), "contextual user input exceeds its text budget");
        ModelInput.UserMessage input = data.isEmpty() ? context.input()
                : new ModelInput.UserMessage(data.append(context.input().text()).toString(), context.input().attachments());
        return new ApiPromptSnapshot(instructions.isEmpty() ? base : base + instructions, input);
    }

    private static void append(StringBuilder instructions, StringBuilder data, String provider, List<PromptSection> sections, boolean instruction)
    {
        for (PromptSection section : sections) {
            String value = instruction ? "\n\n[Instruction " + provider + "/" + section.id() + "]\n" + section.text()
                    : "[Context " + provider + "/" + section.id() + "]\n" + section.text() + "\n[/Context]\n\n";
            checkArgument(value.length() <= PromptContribution.MAX_CHARACTERS - instructions.length() - data.length(), "prompt contributions exceed their character budget");
            (instruction ? instructions : data).append(value);
        }
    }

    void close()
    {
        while (!owned.isEmpty()) {
            owned.getLast().contributor().close();
            owned.removeLast();
        }
    }
}

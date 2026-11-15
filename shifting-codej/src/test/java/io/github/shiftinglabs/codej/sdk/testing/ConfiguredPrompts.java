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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContext;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContribution;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptContributor;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProvider;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptSection;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptSessionContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/** External-package consumer example: a shared factory creates independently owned session sources. */
public final class ConfiguredPrompts
        implements PromptProvider<String>
{
    public final AtomicInteger opened = new AtomicInteger();
    public final AtomicInteger captured = new AtomicInteger();
    public final AtomicInteger closed = new AtomicInteger();
    private final String id;

    public ConfiguredPrompts(String id) { this.id = requireNonNull(id, "id is null"); }

    @Override
    public String id() { return id; }

    @Override
    public PromptContributor open(String setting, PromptSessionContext context)
    {
        opened.incrementAndGet();
        return new PromptContributor()
        {
            private boolean released;

            @Override
            public PromptContribution contribute(PromptContext request)
            {
                captured.incrementAndGet();
                return new PromptContribution(List.of(new PromptSection("rules", setting + " rules")),
                        List.of(new PromptSection("facts", setting + " facts")));
            }

            @Override
            public void close()
            {
                if (!released) {
                    released = true;
                    closed.incrementAndGet();
                }
            }
        };
    }
}

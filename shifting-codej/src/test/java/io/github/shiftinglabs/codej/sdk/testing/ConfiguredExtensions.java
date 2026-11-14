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

import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtension;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionContext;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionProvider;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineObservation;
import io.github.shiftinglabs.codej.cli.spi.extension.ToolPolicyContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/** Upstream example shared by both API providers: restrictive policy plus lifecycle metrics. */
public final class ConfiguredExtensions
        implements EngineExtensionProvider<Boolean>
{
    private final String id;
    public final AtomicInteger opened = new AtomicInteger();
    public final AtomicInteger evaluated = new AtomicInteger();
    public final AtomicInteger closed = new AtomicInteger();
    public final List<EngineObservation> observations = new CopyOnWriteArrayList<>();

    public ConfiguredExtensions(String id) { this.id = requireNonNull(id, "id is null"); }

    @Override
    public String id() { return id; }

    @Override
    public EngineExtension open(Boolean deny, EngineExtensionContext scope)
    {
        opened.incrementAndGet();
        return new EngineExtension()
        {
            private boolean released;

            @Override
            public Decision evaluate(ToolPolicyContext context)
            {
                evaluated.incrementAndGet();
                return deny ? Decision.DENY : Decision.CONTINUE;
            }

            @Override
            public void observe(EngineObservation observation) { observations.add(observation); }

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

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
package io.github.shiftinglabs.codej.cli.api.tools;

import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ResourceTestContext
        implements ToolExecutionContext
{
    public ToolRequirements grants;
    public Optional<Instant> deadline = Optional.empty();
    public boolean cancelled;
    public boolean failJournal;
    public final List<ToolResource> prepared = new ArrayList<>();
    public final List<String> trace = new ArrayList<>();

    public ResourceTestContext(ToolRequirements grants) { this.grants = grants; }

    @Override
    public String invocationId() { return "resource-test"; }

    @Override
    public Optional<Instant> deadline() { return deadline; }

    @Override
    public ToolRequirements grants() { return grants; }

    @Override
    public boolean cancellationRequested() { return cancelled; }

    @Override
    public void recordPrepared(ToolResource resource)
    {
        trace.add("invocation-prepared");
        if (failJournal) {
            throw new IllegalStateException("Journal unavailable");
        }
        prepared.add(resource);
    }
}

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

import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One session owns returned sets immediately, including when catalog inspection fails. */
final class SessionToolSets
{
    private final List<ToolSet> owned = new ArrayList<>();

    List<ToolRegistration> open(ToolProviderPlan plan, ToolSetContext context)
    {
        List<ToolRegistration> result = new ArrayList<>();
        for (var family : plan.families()) {
            ToolSet set = family.binding().open(context);
            owned.add(set);
            List<ToolRegistration> registrations = List.copyOf(set.tools()).stream().map(SessionToolSets::registration).toList();
            if (!registrations.stream().map(ToolRegistration::catalog).toList().equals(family.catalog())) {
                throw new AgentUnavailableException("Opened tool set does not match its advertised contracts");
            }
            result.addAll(registrations);
        }
        return List.copyOf(result);
    }

    private static <P, R> ToolRegistration registration(AgentTool<P, R> tool)
    {
        return ToolRegistration.create(tool, false, true, Optional.empty(), ToolRegistration::failureText);
    }

    void close()
    {
        while (!owned.isEmpty()) {
            owned.getLast().close();
            owned.removeLast();
        }
    }
}

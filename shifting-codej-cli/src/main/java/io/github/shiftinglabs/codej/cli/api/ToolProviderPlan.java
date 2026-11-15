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

import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProviderBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Pure advertised contracts, captured once for this open before profile or resource I/O. */
record ToolProviderPlan(List<Family> families, boolean hasTools)
{
    record Family(ToolProviderBinding binding, List<ToolRegistration.Catalog> catalog)
    {
        Family
        {
            requireNonNull(binding, "binding is null");
            catalog = List.copyOf(catalog);
        }
    }

    ToolProviderPlan { families = List.copyOf(families); }

    static ToolProviderPlan create(List<ToolProviderBinding> bindings, List<Tool> legacy)
    {
        var existing = new ApiToolRegistry(legacy);
        var names = new HashSet<String>();
        existing.definitions().forEach(definition -> names.add(definition.name()));
        List<Family> families = new ArrayList<>();
        for (ToolProviderBinding binding : bindings) {
            List<ToolRegistration.Catalog> catalog = binding.definitions().stream().map(ToolRegistration::catalog).toList();
            for (var definition : catalog) {
                if (!names.add(definition.wire().name())) {
                    throw new AgentUnavailableException("Duplicate API tool name");
                }
            }
            families.add(new Family(binding, catalog));
        }
        return new ToolProviderPlan(families, !names.isEmpty());
    }
}

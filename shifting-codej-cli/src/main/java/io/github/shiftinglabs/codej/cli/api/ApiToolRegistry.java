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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.api.tools.LegacyToolAdapter;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Immutable per-session registrations; wire definitions remain unchanged for existing providers and checkpoints. */
final class ApiToolRegistry
{
    private final Map<String, ToolRegistration> tools;
    private final List<ModelRequest.ToolDefinition> definitions;
    private final List<ToolRegistration.Contract> contracts;
    private final JsonNode wireDefinitions;
    private final JsonNode contractManifest;
    private final boolean legacyContracts;

    ApiToolRegistry(List<Tool> supplied)
    {
        this(index(supplied.stream().map(tool -> ToolRegistration.create(LegacyToolAdapter.builder(tool).build(), true, true,
                Optional.of(tool), failure -> failure.message())).toList()));
    }

    private ApiToolRegistry(Map<String, ToolRegistration> supplied)
    {
        tools = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(supplied));
        definitions = tools.values().stream().map(value -> value.definition).toList();
        contracts = tools.values().stream().map(value -> value.contract).toList();
        wireDefinitions = ApiSessionStore.JSON.valueToTree(definitions.stream()
                .map(tool -> new WireDefinition(tool.name(), tool.description(), tool.inputSchema())).toList());
        contractManifest = ApiSessionStore.JSON.valueToTree(contracts);
        legacyContracts = tools.values().stream().allMatch(tool -> tool.legacy && tool.contract.version() == 1);
    }

    static ApiToolRegistry registered(List<ToolRegistration> tools) { return new ApiToolRegistry(index(tools)); }

    private static Map<String, ToolRegistration> index(List<ToolRegistration> tools)
    {
        Map<String, ToolRegistration> result = new LinkedHashMap<>();
        for (ToolRegistration tool : tools) {
            if (result.putIfAbsent(tool.definition.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate API tool name");
            }
        }
        return result;
    }

    List<ModelRequest.ToolDefinition> definitions() { return definitions; }

    List<ToolRegistration.Contract> contracts() { return contracts; }

    ToolRegistration registration(String name)
    {
        ToolRegistration tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown session tool");
        }
        return tool;
    }

    // Retains the package-level validation helper used by existing registry contracts.
    Tool tool(String name, String arguments)
    {
        ToolRegistration registration = registration(name);
        registration.prepare(arguments, new ToolPreparationContext()
        {
            @Override
            public String invocationId() { return "validation"; }

            @Override
            public Optional<Instant> deadline() { return Optional.empty(); }
        });
        return registration.original.orElseThrow();
    }

    boolean matches(ObjectNode setup)
    {
        requireNonNull(setup, "setup is null");
        // Compare the serialized typed metadata exactly: binding it back could coerce numbers into strings.
        return wireDefinitions.equals(setup.path("tools")) && (setup.has("toolContracts")
                ? contractManifest.equals(setup.path("toolContracts")) : legacyContracts);
    }

    private record WireDefinition(String name, String description, String schema)
    {
        WireDefinition
        {
            requireNonNull(name, "name is null");
            requireNonNull(description, "description is null");
            requireNonNull(schema, "schema is null");
        }
    }
}

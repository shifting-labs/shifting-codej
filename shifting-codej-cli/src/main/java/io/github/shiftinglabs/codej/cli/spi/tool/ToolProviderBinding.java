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
package io.github.shiftinglabs.codej.cli.spi.tool;

import io.github.shiftinglabs.codej.tool.ToolDefinition;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Explicitly bound provider configuration. Configuration must be immutable; diagnostics never render it. */
public final class ToolProviderBinding
{
    private final String id;
    private final Supplier<List<ToolDefinition<?, ?>>> definitions;
    private final Function<ToolSetContext, ToolSet> open;

    private <C> ToolProviderBinding(ToolProvider<C> provider, C configuration)
    {
        requireNonNull(provider, "provider is null");
        requireNonNull(configuration, "configuration is null");
        id = requireNonNull(provider.id(), "provider id is null");
        checkArgument(!id.isBlank() && id.length() <= 128 && id.chars().noneMatch(Character::isISOControl), "invalid tool provider id");
        definitions = () -> List.copyOf(provider.definitions(configuration));
        open = context -> requireNonNull(provider.open(configuration, context), "provider returned no tool set");
    }

    public static <C> ToolProviderBinding of(ToolProvider<C> provider, C configuration) { return new ToolProviderBinding(provider, configuration); }

    public String id() { return id; }

    /** Pure metadata snapshot; the engine validates each contract before opening resources. */
    public List<ToolDefinition<?, ?>> definitions() { return definitions.get(); }

    /** The provider owns cleanup if open throws before returning a tool set. */
    public ToolSet open(ToolSetContext context) { return open.apply(requireNonNull(context, "context is null")); }

    @Override
    public String toString() { return "ToolProviderBinding[" + id + ", configuration redacted]"; }
}

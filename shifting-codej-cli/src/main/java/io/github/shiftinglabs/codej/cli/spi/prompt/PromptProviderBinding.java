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
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A stable provider ID and immutable typed configuration bound without exposing credentials in diagnostics. */
public final class PromptProviderBinding
{
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private final String id;
    private final Function<PromptSessionContext, PromptContributor> open;

    private <C> PromptProviderBinding(PromptProvider<C> provider, C configuration)
    {
        requireNonNull(provider, "provider is null");
        requireNonNull(configuration, "configuration is null");
        id = requireNonNull(provider.id(), "provider id is null");
        checkArgument(ID.matcher(id).matches(), "invalid prompt provider id");
        open = context -> requireNonNull(provider.open(configuration, context), "provider returned no prompt contributor");
    }

    public static <C> PromptProviderBinding of(PromptProvider<C> provider, C configuration) { return new PromptProviderBinding(provider, configuration); }

    public String id() { return id; }

    /** Capture a bounded, ordered provider roster; duplicate IDs cannot shadow another session's source. */
    public static List<PromptProviderBinding> snapshot(List<PromptProviderBinding> bindings)
    {
        List<PromptProviderBinding> captured = List.copyOf(bindings);
        checkArgument(captured.size() <= PromptContribution.MAX_SECTIONS, "too many prompt providers");
        var ids = new HashSet<String>();
        for (PromptProviderBinding binding : captured) {
            checkArgument(ids.add(binding.id()), "duplicate prompt provider id");
        }
        return captured;
    }

    public PromptContributor open(PromptSessionContext context) { return open.apply(requireNonNull(context, "context is null")); }

    @Override
    public String toString() { return "PromptProviderBinding[" + id + ", configuration redacted]"; }
}

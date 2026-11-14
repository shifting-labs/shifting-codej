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

import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtension;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionBinding;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionContext;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineObservation;
import io.github.shiftinglabs.codej.cli.spi.extension.ToolPolicy;
import io.github.shiftinglabs.codej.cli.spi.extension.ToolPolicyContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Owns scopes immediately on return; observers are isolated while policy failures remain fail-closed. */
final class SessionExtensions
{
    private static final class Scope
    {
        final EngineExtension extension;
        boolean observing = true;

        Scope(EngineExtension extension) { this.extension = extension; }
    }

    private final List<Scope> owned = new ArrayList<>();

    boolean isEmpty() { return owned.isEmpty(); }

    void open(List<EngineExtensionBinding> bindings, EngineExtensionContext context)
    {
        for (EngineExtensionBinding binding : bindings) {
            owned.add(new Scope(binding.open(context)));
        }
    }

    Optional<EngineObservation.Admission> rejection(ToolPolicyContext context, Runnable checkStop)
    {
        for (Scope scope : owned) {
            checkStop.run();
            ToolPolicy.Decision decision;
            try {
                decision = requireNonNull(scope.extension.evaluate(context), "policy returned no decision");
            }
            catch (RuntimeException failure) {
                checkStop.run();
                return Optional.of(EngineObservation.Admission.POLICY_UNAVAILABLE);
            }
            checkStop.run();
            if (decision == ToolPolicy.Decision.DENY) {
                return Optional.of(EngineObservation.Admission.POLICY_DENIED);
            }
        }
        return Optional.empty();
    }

    void observe(EngineObservation observation)
    {
        for (Scope scope : owned) {
            if (scope.observing) {
                try {
                    scope.extension.observe(observation);
                }
                catch (Throwable failure) {
                    // Telemetry must never turn already settled work into a retry.
                    scope.observing = false;
                }
            }
        }
    }

    void close()
    {
        while (!owned.isEmpty()) {
            owned.getLast().extension.close();
            owned.removeLast();
        }
    }
}

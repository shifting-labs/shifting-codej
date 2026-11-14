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
package io.github.shiftinglabs.codej.cli.spi.context;

import io.github.shiftinglabs.codej.cli.spi.model.ModelCompaction;

import java.util.Optional;

/**
 * Borrowed, thread-safe host policy producing an optional bounded summary proposal. Runs serially on
 * the existing turn worker, after recovery and before fresh prompt capture. It must be bounded and
 * cooperate with cancellation/interruption; it performs no inference, tool effects or authority changes.
 * The engine never closes this host service. Summary generation with extra model calls is separate.
 */
@FunctionalInterface
public interface ContextCompactor
{
    Optional<ModelCompaction> propose(ContextCompactionContext context);
}

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

import java.util.Optional;

/**
 * Borrowed thread-safe policy selecting an optional engine-budgeted summary. The callback performs
 * no inference or tool effects; the engine owns the isolated model call and all its budgets.
 * Runs after recovery, before fresh prompt capture, and must cooperate with cancellation.
 */
@FunctionalInterface
public interface ContextSummarizationPolicy
{
    Optional<ContextSummarizationPlan> plan(ContextCompactionContext context);
}

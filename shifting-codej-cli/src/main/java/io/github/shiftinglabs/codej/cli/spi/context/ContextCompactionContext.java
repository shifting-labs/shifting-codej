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

import io.github.shiftinglabs.codej.cli.spi.model.ModelContextAssessment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;

import java.time.Instant;
import java.util.Optional;

/** Borrowed private conversation view, valid only during the proposal callback; no execution handles. */
public interface ContextCompactionContext
{
    String turnId();

    String model();

    ModelHistory history();

    /** Current private replay size in UTF-16 characters, not an estimated token count. */
    int replayCharacters();

    Optional<Instant> deadline();

    boolean cancelled();

    /** Present only when a fully projected fresh request exceeded its provider-aware context budget. */
    default Optional<ModelContextAssessment> contextAssessment() { return Optional.empty(); }
}

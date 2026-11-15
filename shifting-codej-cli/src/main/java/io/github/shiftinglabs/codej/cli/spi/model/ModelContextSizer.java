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
package io.github.shiftinglabs.codej.cli.spi.model;

import java.util.Optional;

/**
 * Borrowed thread-safe provider sizing service. Return empty if limits or complete input size are
 * unknown, including unsupported modalities. Include framing, instructions, tools and private native
 * content; report estimates honestly. The callback is local, bounded, interruption/deadline-cooperative
 * and performs no inference, network or tool effects. Never log its private input. The adapter never closes it.
 */
@FunctionalInterface
public interface ModelContextSizer
{
    Optional<ModelContextAssessment> assess(ModelContextInput input);
}

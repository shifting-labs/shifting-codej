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
package io.github.shiftinglabs.codej.tool;

/**
 * Renders typed results without exposing provider objects to tool implementations.
 * Implementations must be thread-safe; the dispatcher applies its output budget and redaction.
 *
 * @param <R> the result type
 */
@FunctionalInterface
public interface ToolResultCodec<R>
{
    /**
     * Encodes a result after execution. Encoding does not execute tool effects.
     *
     * @param result a non-null typed result
     * @return bounded model-facing output
     */
    ToolOutput encode(R result);
}

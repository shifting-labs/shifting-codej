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

import static java.util.Objects.requireNonNull;

/** Standard definition assembly; alternate implementations can implement ToolDefinition directly. */
public final class ToolDefinitions
{
    private ToolDefinitions() {}

    /**
     * Binds metadata to thread-safe, stable codecs without reflection or executing effects.
     *
     * @param <P> parameter type
     * @param <R> result type
     * @param descriptor immutable metadata
     * @param parameters parameter schema and binder
     * @param results result encoder
     * @return the definition
     */
    public static <P, R> ToolDefinition<P, R> of(ToolDescriptor descriptor, ToolParameters<P> parameters, ToolResultCodec<R> results)
    {
        requireNonNull(descriptor, "descriptor is null");
        requireNonNull(parameters, "parameters is null");
        requireNonNull(parameters.schema(), "schema is null");
        requireNonNull(results, "results is null");
        return new Definition<>(descriptor, parameters, results);
    }

    private record Definition<P, R>(ToolDescriptor descriptor, ToolParameters<P> parameters, ToolResultCodec<R> results)
            implements ToolDefinition<P, R>
    {
        @Override
        public String toString() { return "ToolDefinition[" + descriptor.name() + ", version=" + descriptor.contractVersion() + "]"; }
    }
}

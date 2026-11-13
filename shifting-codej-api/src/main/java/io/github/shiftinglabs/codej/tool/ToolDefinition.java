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
 * Side-effect-free logical contract, independent of its execution backend. Metadata and codecs
 * must remain stable for the definition's lifetime. A semantic contract change needs a new version,
 * even when its input schema stays the same.
 *
 * @param <P> parameter type
 * @param <R> result type
 */
public interface ToolDefinition<P, R>
{
    /**
     * Returns stable identity and contract metadata.
     *
     * @return the descriptor
     */
    ToolDescriptor descriptor();

    /**
     * Returns the parameter schema and binder.
     *
     * @return parameter binding
     */
    ToolParameters<P> parameters();

    /**
     * Returns the result encoder.
     *
     * @return result encoding
     */
    ToolResultCodec<R> results();
}

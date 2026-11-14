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
package io.github.shiftinglabs.codej.cli.spi.extension;

/**
 * Shared thread-safe factory for session-owned extensions, configured explicitly without Class tokens.
 *
 * @param <C> immutable provider configuration type
 */
public interface EngineExtensionProvider<C>
{
    /** Pure stable metadata, without I/O or resource acquisition. */
    String id();

    /**
     * Open a fresh scope from immutable configuration. Until returning, the factory owns failure cleanup.
     * Acquisitions must be bounded and must not close services borrowed from the host.
     */
    EngineExtension open(C configuration, EngineExtensionContext context);
}

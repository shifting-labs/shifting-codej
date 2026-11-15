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
 * Session-owned policy and/or observer. Factories return independent instances; callbacks run serially
 * on the existing worker and must cooperate with cancellation/interruption. These are trusted Java
 * extensions, not a sandbox. A policy failure denies; an observer failure disables observation.
 */
public interface EngineExtension
        extends ToolPolicy, EngineObserver, AutoCloseable
{
    @Override
    default Decision evaluate(ToolPolicyContext context) { return Decision.CONTINUE; }

    @Override
    default void observe(EngineObservation observation) {}

    /** Bounded, idempotent cleanup; failed cleanup retains ownership for retry. Never close borrowed services. */
    @Override
    default void close() {}
}

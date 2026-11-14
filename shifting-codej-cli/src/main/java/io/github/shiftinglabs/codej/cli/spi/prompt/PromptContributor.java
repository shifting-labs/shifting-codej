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
package io.github.shiftinglabs.codej.cli.spi.prompt;

/**
 * Session-owned contributor, called serially once per new public turn on the existing turn worker.
 * It may retrieve bounded read-only context, must observe interruption/cancellation/deadlines, and
 * performs no inference or model-requested effects. Returning or throwing ends this callback's work.
 */
@FunctionalInterface
public interface PromptContributor
        extends AutoCloseable
{
    PromptContribution contribute(PromptContext context);

    /**
     * Idempotent, bounded resource release after all callbacks stop. Failure retains ownership for
     * retry. Never close borrowed services. Stateless contributors need no cleanup.
     */
    @Override
    default void close() {}
}

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
package io.github.shiftinglabs.codej.cli.spi.tool;

/**
 * Borrowed host admission service; share one instance across drivers to bound aggregate tool invocations.
 * It creates no workers. This limits invocation count, not CPU, subprocess descendants or model requests.
 */
public interface ToolExecutionBudget
{
    /**
     * Acquire one uniquely owned lease before tool intent/effects. Must cooperate with cancellation,
     * deadlines and interruption. Failed acquisition cleans up its own capacity before throwing.
     *
     * @param context borrowed metadata valid only during acquisition
     * @return one fresh non-null lease; the caller releases it only after work is confirmed stopped
     * @throws InterruptedException when acquisition is interrupted
     * @throws java.util.concurrent.CancellationException when cancelled or the deadline expires
     */
    Lease acquire(Context context)
            throws InterruptedException;

    /** Create a thread-safe bounded budget, without starting threads or acquiring external resources. */
    static ToolExecutionBudget bounded(int maximumConcurrent) { return new BoundedToolExecutionBudget(maximumConcurrent); }

    interface Context
            extends ToolPreparationContext
    {
        boolean cancellationRequested();
    }

    /** A single owned slot. Release must be nonblocking, idempotent and safe to retry after failure. */
    interface Lease
            extends AutoCloseable
    {
        @Override
        void close();
    }
}

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

import java.util.concurrent.CompletionStage;

/**
 * One invocation, separating observed outcome from proof that local work stopped. Completion
 * stages must not expose mutable owner futures; cancelling an observer is not proof of shutdown.
 *
 * @param <R> typed result
 */
public interface ToolExecution<R>
{
    /** Settles once after outcome callbacks finish; cancellation cannot erase observed success. */
    CompletionStage<ToolOutcome<R>> completion();

    /** Requests idempotent nonblocking cancellation; it does not assert that work stopped. */
    void cancel();

    /**
     * Requests stop and verifies all owned work. Bounded by the configured shutdown timeout and
     * retryable after an unverified result. Writer/resource ownership stays held until verified.
     */
    CompletionStage<ToolStopResult> stopAndVerify();

    /**
     * Acknowledges execution resources only after the owning dispatcher durably recorded the
     * outcome. Default preserves existing implementations; a failed acknowledgement can be retried.
     */
    default void committed() {}
}

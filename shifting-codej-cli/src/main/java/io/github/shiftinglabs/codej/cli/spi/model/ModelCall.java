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

import java.util.concurrent.CompletionStage;

/** One inference request; the agent engine maps its outcome into the unchanged RunningTurn contract. */
public interface ModelCall
{
    /**
     * Completes once with a full response, or exceptionally with ModelClientException or
     * CancellationException. A truncated stream is a failure, never a completed response.
     * After completion there are no more sink callbacks. Callers must not complete this stage.
     */
    CompletionStage<ModelResponse> completion();

    /**
     * Requests cancellation without blocking; idempotent and harmless after completion. Completion
     * settles when local streaming has stopped, allowing a terminal response racing cancellation
     * to win. Cancellation does not establish that the remote provider did no work.
     */
    void cancel();
}

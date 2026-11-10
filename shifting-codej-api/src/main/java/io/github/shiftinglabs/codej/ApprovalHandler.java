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
package io.github.shiftinglabs.codej;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Decides a session's {@link Approval}s.
 *
 * <p>Runs on the agent's callback executor. It may answer at once or return a stage that completes
 * when a person decides. A stage that does not complete within {@link SessionOptions#approvalTimeout()},
 * completes exceptionally, or holds a decision outside {@link Approval#choices()} counts as
 * {@link Decision#DENY}, and so does a handler that throws.
 *
 * <p>When the request ends without the handler's decision, because the wait ran out or the provider
 * withdrew it, the stage the handler returned is cancelled, so a person need not answer it any more.
 */
@FunctionalInterface
public interface ApprovalHandler
{
    /** Denies every request; the default. */
    ApprovalHandler DENY_ALL = approval -> CompletableFuture.completedStage(Decision.DENY);

    /**
     * Decides one request.
     *
     * @param approval what the agent asks to do
     * @return the decision, now or later
     */
    CompletionStage<Decision> decide(Approval approval);
}

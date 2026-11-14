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

import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Open notification family; consumers must ignore unrecognized types. No prompts, arguments, replay or output. */
public interface EngineObservation
{
    String turnId();

    record TurnStarted(String turnId)
            implements EngineObservation
    {
        public TurnStarted { checkText(turnId, "turnId"); }
    }

    /** Emitted after the fully received response is durably recorded, including interrupted recovery calls. */
    record ModelCompleted(String turnId, String requestId, ModelResponse.StopReason stopReason, Optional<ProviderUsageSample> usage)
            implements EngineObservation
    {
        public ModelCompleted
        {
            checkText(turnId, "turnId");
            checkText(requestId, "requestId");
            requireNonNull(stopReason, "stopReason is null");
            requireNonNull(usage, "usage is null");
        }
    }

    /** ADMITTED precedes durable admission; it is a decision, not proof that the handler ran. */
    record ToolDecision(String turnId, String invocationId, String toolName, Admission admission)
            implements EngineObservation
    {
        public ToolDecision
        {
            checkText(turnId, "turnId");
            checkText(invocationId, "invocationId");
            checkText(toolName, "toolName");
            requireNonNull(admission, "admission is null");
        }
    }

    /** Emitted after a handler's result is durably recorded and its execution has stopped. */
    record ToolCompleted(String turnId, String invocationId, String toolName, boolean error)
            implements EngineObservation
    {
        public ToolCompleted
        {
            checkText(turnId, "turnId");
            checkText(invocationId, "invocationId");
            checkText(toolName, "toolName");
        }
    }

    /** Selected execution outcome before session cleanup; no output or failure diagnostics. */
    record TurnFinished(String turnId, AgentTurnResult.State state, Duration elapsed)
            implements EngineObservation
    {
        public TurnFinished
        {
            checkText(turnId, "turnId");
            requireNonNull(state, "state is null");
            checkArgument(!requireNonNull(elapsed, "elapsed is null").isNegative(), "negative elapsed duration");
        }
    }

    /** A committed context rewrite; audit history is preserved and summary content is private. */
    record ContextCompacted(String turnId, int summarizedExchanges, int retainedExchanges)
            implements EngineObservation
    {
        public ContextCompacted
        {
            checkText(turnId, "turnId");
            checkArgument(summarizedExchanges > 0 && retainedExchanges >= 0, "invalid compaction counts");
        }
    }

    enum Admission
    {
        INVALID_ARGUMENTS,
        PERMISSION_DENIED,
        POLICY_DENIED,
        POLICY_UNAVAILABLE,
        APPROVAL_DENIED,
        ADMITTED
    }
}

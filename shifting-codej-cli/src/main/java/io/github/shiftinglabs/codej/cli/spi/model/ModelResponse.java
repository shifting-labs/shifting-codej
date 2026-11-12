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

import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** A fully received response. Transport truncation is a failed call, distinct from an observed output limit. */
public final class ModelResponse
{
    private final String text;
    private final List<ToolCall> toolCalls;
    private final StopReason stopReason;
    private final ModelReplayState replayState;
    private final Optional<ProviderUsageSample> usage;

    public ModelResponse(String text, List<ToolCall> toolCalls, StopReason stopReason, ModelReplayState replayState, Optional<ProviderUsageSample> usage)
    {
        this.text = requireNonNull(text, "text is null");
        this.toolCalls = List.copyOf(toolCalls);
        this.stopReason = requireNonNull(stopReason, "stopReason is null");
        this.replayState = requireNonNull(replayState, "replayState is null");
        this.usage = requireNonNull(usage, "usage is null");
        checkArgument((stopReason == StopReason.TOOL_CALLS) == !this.toolCalls.isEmpty(), "only a tool-call stop has executable tool calls");
        checkArgument(this.toolCalls.stream().map(ToolCall::id).distinct().count() == this.toolCalls.size(), "duplicate tool call ID");
        usage.ifPresent(sample -> checkArgument(sample.semantics() == ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                "usage must be final for this model invocation"));
    }

    /** Full displayable text, separate from any private replay content. It may be empty. */
    public String text() { return text; }

    /** Complete, executable client tool batch in provider order; server tools are never dispatched here. */
    public List<ToolCall> toolCalls() { return toolCalls; }

    public StopReason stopReason() { return stopReason; }

    public ModelReplayState replayState() { return replayState; }

    /** Authoritative usage for this request only; absent cost is unknown, not zero. */
    public Optional<ProviderUsageSample> usage() { return usage; }

    public record ToolCall(String id, String name, String arguments)
    {
        public ToolCall
        {
            checkText(id, "tool call ID");
            checkText(name, "tool name");
            ModelJson.checkObject(arguments, "tool arguments");
        }
    }

    /** Only COMPLETED finishes the agent turn successfully; the engine handles every other reason explicitly. */
    public enum StopReason
    {
        COMPLETED,
        TOOL_CALLS,
        OUTPUT_LIMIT,
        REFUSED,
        PAUSED
    }
}

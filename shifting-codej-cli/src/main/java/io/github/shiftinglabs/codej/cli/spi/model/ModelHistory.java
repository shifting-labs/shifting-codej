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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * Immutable provider-neutral history of settled inference exchanges, preceding the request's new
 * input. It contains displayable assistant content and complete tool calls, never provider-private
 * reasoning, signatures or transport credentials. User content and tool data remain sensitive;
 * this is private model input, not a public event transcript or an authorization to change providers.
 */
public final class ModelHistory
{
    private final boolean complete;
    private final List<Exchange> exchanges;

    /**
     * @param complete true when no earlier settled exchanges are missing; false for an unknown
     *                 legacy prefix. A partial history cannot replace native replay.
     * @param exchanges ordered known suffix; an incomplete first exchange may answer legacy tools
     */
    public ModelHistory(boolean complete, List<Exchange> exchanges)
    {
        this.complete = complete;
        this.exchanges = List.copyOf(exchanges);
        var ids = new HashSet<String>();
        Exchange previous = null;
        for (Exchange exchange : this.exchanges) {
            checkArgument(ids.add(exchange.requestId()), "duplicate history request ID");
            validateNext(complete, previous, exchange.input());
            previous = exchange;
        }
    }

    /** Completeness describes the known settled prefix, not the status of a currently submitted request. */
    public boolean complete() { return complete; }

    public List<Exchange> exchanges() { return exchanges; }

    /** Validate the next input without mutating history or interpreting private replay. */
    public void validateNext(ModelInput input)
    {
        validateNext(complete, exchanges.isEmpty() ? null : exchanges.getLast(), input);
    }

    /** Return a new snapshot after a fully observed response; the original remains unchanged. */
    public ModelHistory append(Exchange exchange)
    {
        List<Exchange> next = new ArrayList<>(exchanges);
        next.add(requireNonNull(exchange, "exchange is null"));
        return new ModelHistory(complete, next);
    }

    private static void validateNext(boolean complete, Exchange previous, ModelInput input)
    {
        requireNonNull(input, "input is null");
        if (previous == null && !complete) {
            return;
        }
        List<ModelResponse.ToolCall> pending = previous == null ? List.of() : previous.toolCalls();
        if (input instanceof ModelInput.ToolResults results) {
            checkArgument(!pending.isEmpty() && pending.stream().map(ModelResponse.ToolCall::id).toList()
                    .equals(results.results().stream().map(ModelInput.ToolResult::callId).toList()), "history tool results must answer the entire ordered batch");
        }
        else {
            checkArgument(pending.isEmpty(), "history has unanswered tool calls");
        }
    }

    /**
     * One settled response with its actual submitted input and model provenance. The response text
     * excludes hidden reasoning; stopReason describes the observed response, including refusals or
     * output limits. Tool-call IDs are scoped to this exchange, not globally unique across turns.
     */
    public record Exchange(String requestId, String providerId, String model, ModelInput input, String text,
            List<ModelResponse.ToolCall> toolCalls, ModelResponse.StopReason stopReason)
    {
        public Exchange
        {
            checkText(requestId, "requestId");
            checkText(providerId, "providerId");
            checkText(model, "model");
            requireNonNull(input, "input is null");
            requireNonNull(text, "text is null");
            toolCalls = List.copyOf(toolCalls);
            requireNonNull(stopReason, "stopReason is null");
            checkArgument((stopReason == ModelResponse.StopReason.TOOL_CALLS) == !toolCalls.isEmpty(), "only a tool-call stop has tool calls");
            checkArgument(toolCalls.stream().map(ModelResponse.ToolCall::id).distinct().count() == toolCalls.size(), "duplicate history tool call ID");
        }

        @Override
        public String toString() { return "Exchange[stopReason=" + stopReason + ", redacted]"; }
    }

    @Override
    public String toString() { return "ModelHistory[complete=" + complete + ", exchanges=" + exchanges.size() + ", redacted]"; }

    @Override
    public boolean equals(Object other)
    {
        return other instanceof ModelHistory history && complete == history.complete && exchanges.equals(history.exchanges);
    }

    @Override
    public int hashCode() { return 31 * Boolean.hashCode(complete) + exchanges.hashCode(); }
}

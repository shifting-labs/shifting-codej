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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Immutable inference input. Its builder permits later optional additions without changing a record shape. */
public final class ModelRequest
{
    private final String requestId;
    private final String model;
    private final ModelInput input;
    private final int maximumOutputTokens;
    private final String instructions;
    private final Optional<ModelReplayState> replayState;
    private final List<ToolDefinition> tools;
    private final Optional<String> effort;
    private final Optional<String> serviceTier;
    private final Optional<Instant> deadline;

    private ModelRequest(Builder builder)
    {
        requestId = builder.requestId;
        model = builder.model;
        input = builder.input;
        maximumOutputTokens = builder.maximumOutputTokens;
        instructions = builder.instructions;
        replayState = builder.replayState;
        tools = builder.tools;
        effort = builder.effort;
        serviceTier = builder.serviceTier;
        deadline = builder.deadline;
        checkArgument(!(input instanceof ModelInput.ToolResults) || replayState.isPresent(), "tool results require replay state");
    }

    /** requestId is local correlation, not a provider idempotency guarantee. The token bound must be explicit and positive. */
    public static Builder builder(String requestId, String model, ModelInput input, int maximumOutputTokens)
    {
        return new Builder(requestId, model, input, maximumOutputTokens);
    }

    public String requestId() { return requestId; }

    public String model() { return model; }

    public ModelInput input() { return input; }

    public int maximumOutputTokens() { return maximumOutputTokens; }

    public String instructions() { return instructions; }

    public Optional<ModelReplayState> replayState() { return replayState; }

    public List<ToolDefinition> tools() { return tools; }

    public Optional<String> effort() { return effort; }

    public Optional<String> serviceTier() { return serviceTier; }

    /** Absolute deadline shared with the turn's remaining budget; empty means no caller deadline. */
    public Optional<Instant> deadline() { return deadline; }

    /** Schema and description only: a model adapter never receives a Java tool handler. */
    public record ToolDefinition(String name, String description, String inputSchema)
    {
        public ToolDefinition
        {
            checkText(name, "tool name");
            checkText(description, "tool description");
            ModelJson.checkObject(inputSchema, "inputSchema");
        }
    }

    public static final class Builder
    {
        private final String requestId;
        private final String model;
        private final ModelInput input;
        private final int maximumOutputTokens;
        private String instructions = "";
        private Optional<ModelReplayState> replayState = Optional.empty();
        private List<ToolDefinition> tools = List.of();
        private Optional<String> effort = Optional.empty();
        private Optional<String> serviceTier = Optional.empty();
        private Optional<Instant> deadline = Optional.empty();

        private Builder(String requestId, String model, ModelInput input, int maximumOutputTokens)
        {
            this.requestId = checkText(requestId, "requestId");
            this.model = checkText(model, "model");
            this.input = requireNonNull(input, "input is null");
            checkArgument(maximumOutputTokens > 0, "maximumOutputTokens must be positive");
            this.maximumOutputTokens = maximumOutputTokens;
        }

        public Builder instructions(String instructions)
        {
            this.instructions = requireNonNull(instructions, "instructions is null");
            return this;
        }

        public Builder replayState(ModelReplayState replayState)
        {
            this.replayState = Optional.of(requireNonNull(replayState, "replayState is null"));
            return this;
        }

        public Builder tools(List<ToolDefinition> tools)
        {
            List<ToolDefinition> copy = List.copyOf(tools);
            checkArgument(copy.stream().map(ToolDefinition::name).distinct().count() == copy.size(), "duplicate tool name");
            this.tools = copy;
            return this;
        }

        public Builder effort(String effort)
        {
            this.effort = Optional.of(checkText(effort, "effort"));
            return this;
        }

        public Builder serviceTier(String serviceTier)
        {
            this.serviceTier = Optional.of(checkText(serviceTier, "serviceTier"));
            return this;
        }

        public Builder deadline(Instant deadline)
        {
            this.deadline = Optional.of(requireNonNull(deadline, "deadline is null"));
            return this;
        }

        public ModelRequest build()
        {
            return new ModelRequest(this);
        }
    }
}

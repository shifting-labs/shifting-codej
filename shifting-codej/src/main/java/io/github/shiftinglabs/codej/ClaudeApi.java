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

import io.github.shiftinglabs.codej.cli.spi.context.ContextBudget;
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactor;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionProvider;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextSizer;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProvider;
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetentionPolicy;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.sdk.impl.ApiConfig;
import io.github.shiftinglabs.codej.sdk.impl.DriverAgent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Builds agents using Anthropic's Java SDK and the shared API session engine.
 * Choose {@link Access#TOOLS_ONLY} for trusted host callbacks, or opt into {@link CodingTools} for contained coding sessions.
 * Conversation replay is private local state, so keep the profile folder to resume a {@link SessionRef}.
 *
 * <pre>{@code
 * Agent agent = ClaudeApi.builder().model(modelId).apiKey(() -> secretStore.read("anthropic")).build();
 * }</pre>
 */
public final class ClaudeApi
{
    private ClaudeApi() {}

    /**
     * Creates a builder. A model and an explicit key supplier are required.
     *
     * @return a new builder
     */
    public static Builder builder() { return new Builder(); }

    /** Settings for a direct Claude API agent. Not safe to share between threads. */
    public static final class Builder
    {
        private final ApiConfig.Builder settings = ApiConfig.builder(ApiConfig.Provider.CLAUDE);
        private Optional<Path> state = Optional.empty();
        private Optional<Path> profile = Optional.empty();

        private Builder() {}

        /**
         * Sets the default model identifier; no model release is inferred.
         *
         * @param value the provider's model identifier
         * @return this builder
         */
        public Builder model(String value)
        {
            settings.model(value);
            return this;
        }

        /**
         * Supplies the API key afresh for each HTTP operation. Credentials are never persisted.
         *
         * @param value the credential supplier; environment variables are not consulted
         * @return this builder
         */
        public Builder apiKey(Supplier<String> value)
        {
            settings.apiKey(value);
            return this;
        }

        /**
         * Sets the Messages API base URL. Default: {@code https://api.anthropic.com}.
         *
         * @param value an HTTPS URL, or loopback HTTP for local testing
         * @return this builder
         */
        public Builder baseUrl(URI value)
        {
            settings.baseUrl(value);
            return this;
        }

        /**
         * Sets the private state folder. Default: {@code claude-api} inside the library's state folder.
         *
         * @param value the folder, created when needed
         * @return this builder
         */
        public Builder stateDirectory(Path value)
        {
            state = Optional.of(requireNonNull(value, "stateDirectory is null"));
            return this;
        }

        /**
         * Sets the private replay/checkpoint folder. Default: {@code profile} inside the state folder.
         * Keep separate profiles for different accounts; rotating a key keeps session identity.
         *
         * @param value the persistent profile folder
         * @return this builder
         */
        public Builder profileDirectory(Path value)
        {
            profile = Optional.of(requireNonNull(value, "profileDirectory is null"));
            return this;
        }

        /**
         * Bounds one HTTP operation, including its response stream. Default: two minutes.
         *
         * @param value a positive timeout of at most one hour
         * @return this builder
         */
        public Builder requestTimeout(Duration value)
        {
            settings.requestTimeout(value);
            return this;
        }

        /**
         * Bounds generated output per model request. Default: 4096 tokens.
         *
         * @param value a positive output token limit
         * @return this builder
         */
        public Builder maximumOutputTokens(int value)
        {
            settings.maximumOutputTokens(value);
            return this;
        }

        /**
         * Sets the borrowed executor for callbacks and host tools. Default: virtual threads.
         *
         * @param value the executor; the agent never closes it
         * @return this builder
         */
        public Builder callbackExecutor(Executor value)
        {
            settings.callbackExecutor(value);
            return this;
        }

        /**
         * Enables built-in coding tools for sessions using READ_ONLY or WORKSPACE_WRITE access.
         * All built-in calls require approval. Commands require macOS Seatbelt and cannot fork or use the network.
         *
         * @param value bounded coding tool settings
         * @return this builder
         */
        public Builder codingTools(CodingTools value)
        {
            settings.codingTools(value);
            return this;
        }

        /**
         * Installs a configured tool family in non-text sessions. Every typed call requires approval.
         *
         * @param <C> configuration type, bound explicitly without reflection
         * @param provider tool family; owns cleanup if open fails
         * @param configuration immutable provider configuration
         * @return this builder
         */
        public <C> Builder toolProvider(ToolProvider<C> provider, C configuration)
        {
            settings.toolProvider(provider, configuration);
            return this;
        }

        /**
         * Installs session-owned prompt/context contributors in the shared API engine.
         *
         * @param <C> provider configuration type
         * @param provider thread-safe factory; each session owns its returned contributor
         * @param configuration immutable provider configuration
         * @return this builder
         */
        public <C> Builder promptProvider(PromptProvider<C> provider, C configuration)
        {
            settings.promptProvider(provider, configuration);
            return this;
        }

        /**
         * Installs a session-owned restrictive tool policy and/or metadata observer.
         *
         * @param <C> provider configuration type
         * @param provider thread-safe factory; each session owns its returned extension
         * @param configuration immutable provider configuration
         * @return this builder
         */
        public <C> Builder extension(EngineExtensionProvider<C> provider, C configuration)
        {
            settings.extension(provider, configuration);
            return this;
        }

        /**
         * Borrows a tool admission budget; share one instance across drivers for an aggregate limit.
         * No additional tool workers are created; batch overlap requires a separate explicit opt-in.
         *
         * @param budget thread-safe host service; failed acquisition must release its capacity
         * @return this builder
         */
        public Builder toolExecutionBudget(ToolExecutionBudget budget)
        {
            settings.toolExecutionBudget(budget);
            return this;
        }

        /**
         * Enables a bounded batch window for explicitly eligible asynchronous tools. Defaults to one.
         * Writes, opaque effects, legacy tools and built-ins remain exclusive.
         *
         * @param value maximum active tools per batch, from 1 to 64; greater than one requires a shared budget
         * @return this builder
         */
        public Builder maximumParallelTools(int value)
        {
            settings.maximumParallelTools(value);
            return this;
        }

        /**
         * Borrows a bounded local summary policy. Compaction is opt-in and preserves durable history.
         *
         * @param compactor thread-safe host service; it performs no inference or tool effects
         * @return this builder
         */
        public Builder contextCompactor(ContextCompactor compactor)
        {
            settings.contextCompactor(compactor);
            return this;
        }

        /**
         * Borrows a policy enabling bounded tool-free summary inferences charged to the current turn.
         * Cannot be combined with a local context compactor. Disabled by default.
         *
         * @param policy thread-safe selector; the engine performs inference with its existing budgets
         * @return this builder
         */
        public Builder contextSummarization(ContextSummarizationPolicy policy)
        {
            settings.contextSummarization(policy);
            return this;
        }

        /**
         * Bounds each encoded summary input, including prior memory and complete new tool groups.
         * The engine text limit also applies; an indivisible oversized group fails without truncation.
         *
         * @param value positive maximum UTF-16 character count; defaults to 65,536, not a token estimate
         * @return this builder
         */
        public Builder maximumSummaryInputCharacters(int value)
        {
            settings.maximumSummaryInputCharacters(value);
            return this;
        }

        /**
         * Borrows an opt-in audit retention policy. Covered history is irreversibly removed;
         * physical staged-attachment cleanup requires a separately positive deletion bound.
         *
         * @param policy thread-safe host selector; the engine performs safe commits and owned cleanup
         * @return this builder
         */
        public Builder auditRetention(AuditRetentionPolicy policy)
        {
            settings.auditRetention(policy);
            return this;
        }

        /**
         * Enables provider-aware preflight of actual input plus reserved output and a safety margin.
         * Disabled by default; uses built-in text estimates unless a custom sizer is installed.
         *
         * @param budget immutable policy, including explicit handling of unknown sizes
         * @return this builder
         */
        public Builder contextBudget(ContextBudget budget)
        {
            settings.contextBudget(budget);
            return this;
        }

        /**
         * Borrows a local provider-specific sizing service, overriding built-in estimates even when it returns unknown.
         *
         * @param sizer thread-safe service receiving private, adapter-projected active input
         * @return this builder
         */
        public Builder contextSizer(ModelContextSizer sizer)
        {
            settings.contextSizer(sizer);
            return this;
        }

        /**
         * Builds the agent without sending a request or starting a CLI.
         *
         * @return the agent; close it to stop its sessions and HTTP workers
         * @throws IllegalArgumentException for invalid settings
         * @throws NullPointerException when a model or credential supplier is missing
         */
        public Agent build()
        {
            return DriverAgent.api(settings.build(), state, profile);
        }
    }
}

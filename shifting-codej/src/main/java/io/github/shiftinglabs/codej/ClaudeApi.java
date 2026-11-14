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

import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionBinding;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionProvider;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProvider;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProviderBinding;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProviderBinding;
import io.github.shiftinglabs.codej.sdk.impl.ClaudeApiConfig;
import io.github.shiftinglabs.codej.sdk.impl.DriverAgent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        private String model;
        private Supplier<String> key;
        private URI baseUrl = URI.create("https://api.anthropic.com");
        private Optional<Path> state = Optional.empty();
        private Optional<Path> profile = Optional.empty();
        private Duration timeout = Duration.ofMinutes(2);
        private int maximumOutputTokens = 4096;
        private Optional<Executor> callbacks = Optional.empty();
        private Optional<CodingTools> codingTools = Optional.empty();
        private final List<ToolProviderBinding> toolProviders = new ArrayList<>();
        private final List<PromptProviderBinding> promptProviders = new ArrayList<>();
        private final List<EngineExtensionBinding> extensions = new ArrayList<>();
        private Optional<ToolExecutionBudget> toolExecutionBudget = Optional.empty();
        private int maximumParallelTools = 1;

        private Builder() {}

        /**
         * Sets the default model identifier; no model release is inferred.
         *
         * @param value the provider's model identifier
         * @return this builder
         */
        public Builder model(String value)
        {
            model = requireNonNull(value, "model is null");
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
            key = requireNonNull(value, "apiKey is null");
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
            baseUrl = requireNonNull(value, "baseUrl is null");
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
            timeout = requireNonNull(value, "requestTimeout is null");
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
            maximumOutputTokens = value;
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
            callbacks = Optional.of(requireNonNull(value, "callbackExecutor is null"));
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
            codingTools = Optional.of(requireNonNull(value, "codingTools is null"));
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
            toolProviders.add(ToolProviderBinding.of(provider, configuration));
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
            promptProviders.add(PromptProviderBinding.of(provider, configuration));
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
            extensions.add(EngineExtensionBinding.of(provider, configuration));
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
            toolExecutionBudget = Optional.of(requireNonNull(budget, "toolExecutionBudget is null"));
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
            if (value < 1 || value > 64) {
                throw new IllegalArgumentException("parallel tool window must be between 1 and 64");
            }
            maximumParallelTools = value;
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
            var settings = ClaudeApiConfig.builder(requireNonNull(model, "model is required"), requireNonNull(key, "apiKey is required"))
                    .baseUrl(baseUrl).requestTimeout(timeout).maximumOutputTokens(maximumOutputTokens);
            callbacks.ifPresent(settings::callbackExecutor);
            codingTools.ifPresent(settings::codingTools);
            toolProviders.forEach(settings::toolProvider);
            promptProviders.forEach(settings::promptProvider);
            extensions.forEach(settings::extension);
            toolExecutionBudget.ifPresent(settings::toolExecutionBudget);
            settings.maximumParallelTools(maximumParallelTools);
            return DriverAgent.claudeApi(settings.build(), state, profile);
        }
    }
}

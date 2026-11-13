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

import io.github.shiftinglabs.codej.sdk.impl.ClaudeApiConfig;
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
 * Choose {@link Access#TOOLS_ONLY} explicitly for these sessions; tools are trusted host callbacks.
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
            return DriverAgent.claudeApi(settings.build(), state, profile);
        }
    }
}

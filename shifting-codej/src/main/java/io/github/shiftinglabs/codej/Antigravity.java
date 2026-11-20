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

import io.github.shiftinglabs.codej.cli.antigravity.AntigravityCliDriver;
import io.github.shiftinglabs.codej.sdk.impl.AntigravityConnectorConfig;
import io.github.shiftinglabs.codej.sdk.impl.DriverAgent;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Builds agents that run Google's Antigravity CLI ({@code agy}). */
public final class Antigravity
{
    /** The oldest Antigravity CLI release supported by this connector. */
    public static final String MINIMUM_VERSION = AntigravityCliDriver.MINIMUM_VERSION;

    private Antigravity() {}

    /**
     * Starts a builder that looks for {@code agy} on the PATH.
     *
     * @return a new builder
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /** Settings for one Antigravity agent. */
    public static final class Builder
    {
        private Optional<Path> executable = Optional.empty();
        private Optional<Path> configDir = Optional.empty();
        private Optional<Path> stateDirectory = Optional.empty();
        private Optional<Supplier<String>> apiKey = Optional.empty();
        private Optional<Executor> callbackExecutor = Optional.empty();

        private Builder() {}

        /**
         * Selects a local Antigravity CLI executable.
         *
         * @param executable path to {@code agy}
         * @return this builder
         */
        public Builder executable(Path executable)
        {
            this.executable = Optional.of(executable);
            return this;
        }

        /**
         * Selects the private profile directory for this agent. Defaults to {@code antigravity}
         * inside the {@linkplain #stateDirectory(Path) state directory}.
         *
         * @param directory the profile directory
         * @return this builder
         */
        public Builder configDir(Path directory)
        {
            this.configDir = Optional.of(directory);
            return this;
        }

        /**
         * Selects the directory for working files and process records.
         * Defaults to {@code ~/Library/Application Support/shifting-codej}.
         *
         * @param directory the state directory
         * @return this builder
         */
        public Builder stateDirectory(Path directory)
        {
            this.stateDirectory = Optional.of(directory);
            return this;
        }

        /**
         * Authenticates the CLI with the Gemini API key from {@code GEMINI_API_KEY}.
         *
         * @return this builder
         */
        public Builder apiKey()
        {
            return apiKey(() -> System.getenv("GEMINI_API_KEY"));
        }

        /**
         * Authenticates the CLI with a Gemini API key.
         *
         * @param key supplies the key for the CLI's environment
         * @return this builder
         */
        public Builder apiKey(Supplier<String> key)
        {
            this.apiKey = Optional.of(key);
            return this;
        }

        /**
         * Sets where callbacks run, as {@link Codex.Builder#callbackExecutor(Executor)} does.
         *
         * @param executor runs the callbacks
         * @return this builder
         */
        public Builder callbackExecutor(Executor executor)
        {
            this.callbackExecutor = Optional.of(executor);
            return this;
        }

        /**
         * Builds the agent and prepares its private state. The CLI starts on demand.
         *
         * @return the agent
         * @throws AgentException when the state or profile directory cannot be prepared
         */
        public Agent build()
        {
            return DriverAgent.antigravity(new AntigravityConnectorConfig(executable, apiKey), stateDirectory, configDir, callbackExecutor);
        }
    }
}

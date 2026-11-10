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

import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.sdk.impl.DriverAgent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * Builds agents that run OpenAI's Codex through its App Server.
 *
 * <pre>{@code
 * Agent codex = Codex.builder().build();
 * Agent work = Codex.builder().home(workHome).account("me@example.com").build();
 * Agent proxy = Codex.builder().gateway(URI.create("https://llm.example.com/v1"), vault::gatewayToken).build();
 * }</pre>
 */
public final class Codex
{
    /** The Codex release this library is built and tested against. */
    public static final String VERSION = "0.164.0";

    private Codex() {}

    /**
     * Starts a builder with the defaults: {@code codex} from the {@code PATH}, a private home in the
     * {@linkplain Builder#stateDirectory(Path) state folder} and a ChatGPT login.
     *
     * @return a new builder
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /** Settings for a Codex agent. Not safe to share between threads. */
    public static final class Builder
    {
        private Optional<Path> executable = Optional.empty();
        private Optional<Path> home = Optional.empty();
        private Optional<Path> stateDirectory = Optional.empty();
        private Optional<String> account = Optional.empty();
        private Optional<URI> gatewayUrl = Optional.empty();
        private Optional<Supplier<String>> gatewayToken = Optional.empty();
        private Optional<Supplier<String>> deepSeekKey = Optional.empty();
        private Optional<Duration> keepSessions = Optional.empty();
        private Optional<Executor> callbackExecutor = Optional.empty();
        private ToolServer toolServer = ToolServer.DEFAULT;

        private Builder() {}

        /**
         * Runs this Codex executable instead of downloading one. It must report {@link #VERSION} or newer; a newer release can
         * change the App Server protocol, so tested support covers {@link #VERSION} only.
         *
         * @param codex the executable
         * @return this builder
         */
        public Builder executable(Path codex)
        {
            this.executable = Optional.of(codex);
            return this;
        }

        /**
         * Sets Codex's home folder, which holds its login and settings. Default: {@code codex} in the
         * {@linkplain #stateDirectory(Path) state folder}, never {@code ~/.codex}, so the user's own
         * Codex settings and rules never reach a session.
         *
         * @param home the folder; created when missing
         * @return this builder
         */
        public Builder home(Path home)
        {
            this.home = Optional.of(home);
            return this;
        }

        /**
         * Sets the folder where this agent keeps its sessions' working files and the records of the
         * processes it starts. Default: {@code ~/Library/Application Support/shifting-codej}.
         *
         * @param directory the folder; created when missing
         * @return this builder
         */
        public Builder stateDirectory(Path directory)
        {
            this.stateDirectory = Optional.of(directory);
            return this;
        }

        /**
         * Requires the ChatGPT login in the {@linkplain #home(Path) home} to have this email: sessions
         * refuse to start under another account. Use a separate home for each account.
         *
         * @param email the account's email
         * @return this builder
         */
        public Builder account(String email)
        {
            this.account = Optional.of(email);
            return this;
        }

        /**
         * Sends requests to a Responses-compatible gateway, such as a LiteLLM proxy, instead of using
         * a ChatGPT login.
         *
         * @param baseUrl the gateway's API base, such as {@code https://llm.example.com/v1}
         * @param token read each time a process starts, passed only in that process's environment,
         *        and never written to disk
         * @return this builder
         */
        public Builder gateway(URI baseUrl, Supplier<String> token)
        {
            this.gatewayUrl = Optional.of(baseUrl);
            this.gatewayToken = Optional.of(token);
            return this;
        }

        /**
         * Runs DeepSeek models through a local Responses adapter instead of using a ChatGPT login.
         *
         * @param apiKey read as {@link #gateway(URI, Supplier)} reads its token
         * @return this builder
         */
        public Builder deepSeek(Supplier<String> apiKey)
        {
            this.deepSeekKey = Optional.of(apiKey);
            return this;
        }

        /**
         * Deletes the transcripts of this agent's conversations once they were last used longer ago than {@code age}, while the agent
         * is open: first two minutes after it is built, then every six hours. A deleted conversation can no longer be resumed. Agents
         * that share a home share one sweep, which keeps the shortest time any of them sets. Default: nothing is deleted, as the CLI
         * keeps every transcript.
         *
         * @param age how long a conversation's transcript is kept after its last use
         * @return this builder
         * @throws IllegalArgumentException when {@code age} is not positive
         */
        public Builder keepSessionsFor(Duration age)
        {
            checkArgument(age.isPositive(), "age is not positive: %s", age);
            this.keepSessions = Optional.of(age);
            return this;
        }

        /**
         * Names the MCP server through which the model reaches your {@link Tool}s. The name is part of every tool name the model
         * sees, such as {@code mcp__<name>__<tool>} in Claude and the {@code <name>_call_tool} catalog. Default: {@code codej}.
         *
         * @param name lowercase letters and digits, single underscores between them, at most 32 characters
         * @return this builder
         * @throws IllegalArgumentException when {@code name} breaks those rules
         */
        public Builder toolServerName(String name)
        {
            this.toolServer = new ToolServer(name);
            return this;
        }

        /**
         * Sets where listeners and approval, question and tool handlers run. Default: a new virtual
         * thread for each callback, with each turn's events kept in order.
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
         * Builds the agent. Starts no process. Without {@link #executable(Path)}, the first build on this Mac downloads OpenAI's
         * Codex {@link #VERSION}, about 120 MB, from npm: from {@code https://registry.npmjs.org/}, or from the mirror the system
         * property {@code codej.codex.registry} or the environment variable {@code CODEJ_CODEX_REGISTRY} names. The download must
         * match the SHA-512 and file checksums this library records. It is kept in {@code ~/Library/Caches/shifting-codej/codex},
         * so later builds reuse it without a request; a release no build has used for thirty days is deleted. When the download
         * fails, the agent is still built and {@link Agent#status()} says why.
         *
         * @return the agent; close it to stop its sessions
         * @throws IllegalStateException when more than one of {@link #account}, {@link #gateway} and {@link #deepSeek} is set
         * @throws AgentException when the home or the state folder cannot be prepared
         */
        public Agent build()
        {
            long logins = (account.isPresent() ? 1 : 0) + (gatewayUrl.isPresent() ? 1 : 0) + (deepSeekKey.isPresent() ? 1 : 0);
            checkState(logins <= 1, "Choose one of account, gateway and deepSeek");
            return DriverAgent.codex(new DriverAgent.CodexSettings(
                    executable, home, stateDirectory, account, gatewayUrl.map(url -> new DriverAgent.Gateway(url, gatewayToken.orElseThrow())), deepSeekKey,
                    keepSessions, callbackExecutor, toolServer));
        }
    }
}

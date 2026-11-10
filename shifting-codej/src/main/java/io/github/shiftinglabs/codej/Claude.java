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

import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.sdk.impl.DriverAgent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * Builds agents that run Anthropic's Claude Code CLI.
 *
 * <pre>{@code
 * Agent claude = Claude.builder().build();
 * Agent work = Claude.builder().configDir(workConfig).account("me@example.com").build();
 * }</pre>
 */
public final class Claude
{
    /** The oldest Claude Code release this library supports. */
    public static final String MINIMUM_VERSION = "2.1.200";

    private Claude() {}

    /**
     * Starts a builder with the defaults: {@code claude} from the {@code PATH}, a private config folder
     * in the {@linkplain Builder#stateDirectory(Path) state folder} and a Claude login.
     *
     * @return a new builder
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /** Settings for a Claude agent. Not safe to share between threads. */
    public static final class Builder
    {
        private Optional<Path> executable = Optional.empty();
        private Optional<Path> configDir = Optional.empty();
        private Optional<Path> stateDirectory = Optional.empty();
        private Optional<String> account = Optional.empty();
        private Optional<URI> gatewayUrl = Optional.empty();
        private Optional<Supplier<String>> gatewayToken = Optional.empty();
        private final ImmutableList.Builder<String> outsideSandbox = ImmutableList.builder();
        private final ImmutableList.Builder<Path> unixSockets = ImmutableList.builder();
        private final ImmutableList.Builder<String> autoModeContext = ImmutableList.builder();
        private final ImmutableList.Builder<String> autoModeRules = ImmutableList.builder();
        private boolean reviewSandboxedCommands;
        private Optional<Duration> keepSessions = Optional.empty();
        private Optional<Executor> callbackExecutor = Optional.empty();
        private ToolServer toolServer = ToolServer.DEFAULT;

        private Builder() {}

        /**
         * Runs this executable. Default: {@code claude} found on the {@code PATH}.
         *
         * @param claude the executable
         * @return this builder
         */
        public Builder executable(Path claude)
        {
            this.executable = Optional.of(claude);
            return this;
        }

        /**
         * Sets Claude Code's config folder, which holds its login and settings. Default: {@code claude}
         * in the {@linkplain #stateDirectory(Path) state folder}, never {@code ~/.claude}. Each folder
         * holds one login.
         *
         * @param dir the folder; created when missing
         * @return this builder
         */
        public Builder configDir(Path dir)
        {
            this.configDir = Optional.of(dir);
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
         * Requires the login in the {@linkplain #configDir(Path) config folder} to have this email:
         * sessions refuse to start under another account.
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
         * Sends requests to an Anthropic-compatible gateway instead of using a Claude login.
         *
         * @param baseUrl the gateway's base URL
         * @param token read once, when {@link #build()} runs; passed only in the CLI's environment,
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
         * Runs these programs outside Claude's sandbox when a command is that program alone, such as
         * {@code mvn}, which cannot download through the sandbox's network proxy. It applies to
         * sessions that {@linkplain SessionOptions.Builder#allowNetwork() allow the network}. Each
         * such command still needs approval, and then has the user's file and network access.
         *
         * @param programs program names, such as {@code mvn} or {@code ./mvnw}
         * @return this builder
         */
        public Builder runOutsideSandbox(String... programs)
        {
            outsideSandbox.add(programs);
            return this;
        }

        /**
         * Lets sandboxed commands use this Unix socket, such as Docker's, so tests can start
         * containers. It applies to sessions that {@linkplain SessionOptions.Builder#allowNetwork()
         * allow the network}. Control of the daemon is not confined to the working directory.
         *
         * @param socket the socket file
         * @return this builder
         */
        public Builder allowUnixSocket(Path socket)
        {
            unixSockets.add(socket);
            return this;
        }

        /**
         * Adds rules for Claude's auto-mode reviewer, which checks the agent's actions before they run. Claude's own
         * default rules stay. With a rule, the reviewer checks every session of this agent.
         *
         * @param rules what the reviewer must refuse, in plain words
         * @return this builder
         */
        public Builder autoModeRules(String... rules)
        {
            autoModeRules.add(rules);
            return this;
        }

        /**
         * Tells Claude's auto-mode reviewer more about the sessions it checks, besides its default context. Needs
         * {@link #autoModeRules}.
         *
         * @param context what the reviewer should know, such as which hosts the work may reach
         * @return this builder
         */
        public Builder autoModeContext(String... context)
        {
            autoModeContext.add(context);
            return this;
        }

        /**
         * Has Claude's reviewer check commands that run inside the sandbox too; without it, they run unchecked.
         *
         * @return this builder
         */
        public Builder reviewSandboxedCommands()
        {
            this.reviewSandboxedCommands = true;
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
         * Names the MCP server through which the model reaches your {@link Tool}s, as {@link Codex.Builder#toolServerName(String)}
         * does.
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
         * Builds the agent. Starts no process.
         *
         * @return the agent; close it to stop its sessions
         * @throws IllegalStateException when both {@link #account} and {@link #gateway} are set, or
         *         {@link #autoModeContext} is set without {@link #autoModeRules}
         * @throws AgentException when the config folder or the state folder cannot be prepared
         */
        public Agent build()
        {
            checkState(account.isEmpty() || gatewayUrl.isEmpty(), "Choose one of account and gateway");
            List<String> rules = autoModeRules.build();
            List<String> context = autoModeContext.build();
            checkState(!rules.isEmpty() || context.isEmpty(), "Auto-mode context needs a rule");
            return DriverAgent.claude(new DriverAgent.ClaudeSettings(
                    executable, configDir, stateDirectory, account, gatewayUrl.map(url -> new DriverAgent.Gateway(url, gatewayToken.orElseThrow())),
                    outsideSandbox.build(), unixSockets.build(), context, rules, reviewSandboxedCommands, keepSessions, callbackExecutor, toolServer));
        }
    }
}

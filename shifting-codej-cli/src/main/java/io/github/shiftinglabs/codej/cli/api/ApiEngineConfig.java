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
package io.github.shiftinglabs.codej.cli.api;

import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Immutable runtime configuration. Each driver owns the fresh client its factory returns. */
public final class ApiEngineConfig
{
    final ConnectorIdentity identity;
    final AgentLaneId lane;
    final String model;
    final Supplier<ModelClient> clients;
    final int maximumSessions;
    final int maximumRequests;
    final int maximumToolCalls;
    final int maximumOutputTokens;
    final long maximumTokens;
    final int maximumTextChars;
    final int maximumToolOutputChars;
    final int maximumCheckpointBytes;
    final long maximumAttachmentBytes;
    final Duration shutdownTimeout;
    final boolean approveTools;
    final Optional<ExecutorService> executor;
    final SecretRedactor redactor;
    final Supplier<SecretRedactor> redactors;
    final Optional<Executor> toolExecutor;

    private ApiEngineConfig(Builder b)
    {
        identity = b.identity;
        lane = b.lane;
        model = b.model;
        clients = b.clients;
        maximumSessions = b.maximumSessions;
        maximumRequests = b.maximumRequests;
        maximumToolCalls = b.maximumToolCalls;
        maximumOutputTokens = b.maximumOutputTokens;
        maximumTokens = b.maximumTokens;
        maximumTextChars = b.maximumTextChars;
        maximumToolOutputChars = b.maximumToolOutputChars;
        maximumCheckpointBytes = b.maximumCheckpointBytes;
        maximumAttachmentBytes = b.maximumAttachmentBytes;
        shutdownTimeout = b.shutdownTimeout;
        approveTools = b.approveTools;
        executor = b.executor;
        redactor = b.redactor;
        redactors = b.redactors.orElse(() -> redactor);
        toolExecutor = b.toolExecutor;
    }

    SecretRedactor redactor() { return requireNonNull(redactors.get(), "redactor supplier returned null"); }

    public static Builder builder(ConnectorIdentity identity, AgentLaneId lane, String model, Supplier<ModelClient> clients)
    {
        return new Builder(identity, lane, model, clients);
    }

    public static final class Builder
    {
        private final ConnectorIdentity identity;
        private final AgentLaneId lane;
        private final String model;
        private final Supplier<ModelClient> clients;
        private int maximumSessions = 4;
        private int maximumRequests = 32;
        private int maximumToolCalls = 64;
        private int maximumOutputTokens = 4096;
        private long maximumTokens = 131072;
        private int maximumTextChars = 1024 * 1024;
        private int maximumToolOutputChars = 65536;
        private int maximumCheckpointBytes = 4 * 1024 * 1024;
        private long maximumAttachmentBytes = 8 * 1024 * 1024;
        private Duration shutdownTimeout = Duration.ofSeconds(2);
        private boolean approveTools = true;
        private Optional<ExecutorService> executor = Optional.empty();
        private Optional<Supplier<SecretRedactor>> redactors = Optional.empty();
        private Optional<Executor> toolExecutor = Optional.empty();
        private SecretRedactor redactor = SecretRedactor.fromEnvironment(Map.of());

        private Builder(ConnectorIdentity identity, AgentLaneId lane, String model, Supplier<ModelClient> clients)
        {
            this.identity = requireNonNull(identity, "identity is null");
            this.lane = requireNonNull(lane, "lane is null");
            this.model = checkText(model, "model");
            this.clients = requireNonNull(clients, "clients is null");
        }

        public Builder maximumSessions(int value)
        {
            maximumSessions = positive(value);
            return this;
        }

        public Builder maximumRequests(int value)
        {
            maximumRequests = positive(value);
            return this;
        }

        public Builder maximumToolCalls(int value)
        {
            maximumToolCalls = positive(value);
            return this;
        }

        public Builder maximumOutputTokens(int value)
        {
            maximumOutputTokens = positive(value);
            return this;
        }

        /** Observed input/output usage budget; providers cannot guarantee an exact input-token ceiling before submission. */
        public Builder maximumTokens(long value)
        {
            checkArgument(value > 0, "limit must be positive");
            maximumTokens = value;
            return this;
        }

        public Builder maximumTextChars(int value)
        {
            maximumTextChars = positive(value);
            return this;
        }

        public Builder maximumToolOutputChars(int value)
        {
            maximumToolOutputChars = positive(value);
            return this;
        }

        public Builder maximumCheckpointBytes(int value)
        {
            maximumCheckpointBytes = positive(value);
            return this;
        }

        public Builder maximumAttachmentBytes(long value)
        {
            checkArgument(value > 0, "limit must be positive");
            maximumAttachmentBytes = value;
            return this;
        }

        public Builder shutdownTimeout(Duration value)
        {
            requireNonNull(value, "shutdownTimeout is null");
            checkArgument(!value.isNegative() && !value.isZero() && value.compareTo(Duration.ofMinutes(1)) <= 0, "invalid shutdown timeout");
            shutdownTimeout = value;
            return this;
        }

        /** False opts into automatic invocation of host-supplied capabilities; callbacks remain trusted host code. */
        public Builder approveTools(boolean value)
        {
            approveTools = value;
            return this;
        }

        /** Borrowed executor; the driver never closes it. Tool handlers run on the turn worker. */
        public Builder executor(ExecutorService value)
        {
            executor = Optional.of(requireNonNull(value, "executor is null"));
            return this;
        }

        public Builder redactor(SecretRedactor value)
        {
            redactor = requireNonNull(value, "redactor is null");
            redactors = Optional.empty();
            return this;
        }

        /** Supplies the current redactor, including credentials refreshed by a provider adapter. */
        public Builder redactorSupplier(Supplier<SecretRedactor> value)
        {
            redactors = Optional.of(requireNonNull(value, "redactorSupplier is null"));
            return this;
        }

        /** Borrowed executor for trusted host tools; cancellation waits for an executing handler to finish. */
        public Builder toolExecutor(Executor value)
        {
            toolExecutor = Optional.of(requireNonNull(value, "toolExecutor is null"));
            return this;
        }

        public ApiEngineConfig build() { return new ApiEngineConfig(this); }

        private static int positive(int value)
        {
            checkArgument(value > 0, "limit must be positive");
            return value;
        }
    }
}

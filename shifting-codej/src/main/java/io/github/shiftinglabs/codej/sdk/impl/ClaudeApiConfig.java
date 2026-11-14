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
package io.github.shiftinglabs.codej.sdk.impl;

import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactor;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionBinding;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionProvider;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProvider;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProviderBinding;
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetentionPolicy;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProviderBinding;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Immutable direct Claude API configuration; no environment credentials or CLI settings are inferred. */
public final class ClaudeApiConfig
{
    final String model;
    final Supplier<String> key;
    final URI baseUrl;
    final Duration timeout;
    final int maximumOutputTokens;
    final Optional<Executor> callbacks;
    final Optional<CodingTools> codingTools;
    final List<ToolProviderBinding> toolProviders;
    final List<PromptProviderBinding> promptProviders;
    final List<EngineExtensionBinding> extensions;
    final Optional<ToolExecutionBudget> toolExecutionBudget;
    final int maximumParallelTools;
    final Optional<ContextCompactor> contextCompactor;
    final Optional<ContextSummarizationPolicy> contextSummarization;
    final int maximumSummaryInputCharacters;
    final Optional<AuditRetentionPolicy> auditRetention;

    private ClaudeApiConfig(Builder builder)
    {
        model = builder.model;
        key = builder.key;
        baseUrl = builder.baseUrl;
        timeout = builder.timeout;
        maximumOutputTokens = builder.maximumOutputTokens;
        callbacks = builder.callbacks;
        codingTools = builder.codingTools;
        toolProviders = List.copyOf(builder.toolProviders);
        promptProviders = PromptProviderBinding.snapshot(builder.promptProviders);
        extensions = EngineExtensionBinding.snapshot(builder.extensions);
        toolExecutionBudget = builder.toolExecutionBudget;
        maximumParallelTools = builder.maximumParallelTools;
        contextCompactor = builder.contextCompactor;
        contextSummarization = builder.contextSummarization;
        maximumSummaryInputCharacters = builder.maximumSummaryInputCharacters;
        auditRetention = builder.auditRetention;
        checkArgument(contextCompactor.isEmpty() || contextSummarization.isEmpty(), "choose local compaction or engine summarization");
        checkArgument(maximumParallelTools == 1 || toolExecutionBudget.isPresent(), "parallel tools require a shared execution budget");
    }

    public static Builder builder(String model, Supplier<String> key) { return new Builder(model, key); }

    public static final class Builder
    {
        private final String model;
        private final Supplier<String> key;
        private URI baseUrl = URI.create("https://api.anthropic.com");
        private Duration timeout = Duration.ofMinutes(2);
        private int maximumOutputTokens = 4096;
        private Optional<Executor> callbacks = Optional.empty();
        private Optional<CodingTools> codingTools = Optional.empty();
        private final List<ToolProviderBinding> toolProviders = new ArrayList<>();
        private final List<PromptProviderBinding> promptProviders = new ArrayList<>();
        private final List<EngineExtensionBinding> extensions = new ArrayList<>();
        private Optional<ToolExecutionBudget> toolExecutionBudget = Optional.empty();
        private int maximumParallelTools = 1;
        private Optional<ContextCompactor> contextCompactor = Optional.empty();
        private Optional<ContextSummarizationPolicy> contextSummarization = Optional.empty();
        private int maximumSummaryInputCharacters = 65536;
        private Optional<AuditRetentionPolicy> auditRetention = Optional.empty();

        private Builder(String model, Supplier<String> key)
        {
            this.model = checkText(model, "model");
            checkArgument(model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"), "invalid model identifier");
            this.key = requireNonNull(key, "key is null");
        }

        public Builder baseUrl(URI value)
        {
            baseUrl = ApiHttpTransport.checkBaseUrl(value);
            return this;
        }

        public Builder requestTimeout(Duration value)
        {
            requireNonNull(value, "timeout is null");
            checkArgument(!value.isNegative() && !value.isZero() && value.compareTo(Duration.ofHours(1)) <= 0, "invalid API timeout");
            timeout = value;
            return this;
        }

        public Builder maximumOutputTokens(int value)
        {
            checkArgument(value > 0, "maximumOutputTokens must be positive");
            maximumOutputTokens = value;
            return this;
        }

        public Builder callbackExecutor(Executor value)
        {
            callbacks = Optional.of(requireNonNull(value, "executor is null"));
            return this;
        }

        public Builder codingTools(CodingTools value)
        {
            codingTools = Optional.of(requireNonNull(value, "codingTools is null"));
            return this;
        }

        public <C> Builder toolProvider(ToolProvider<C> provider, C configuration)
        {
            return toolProvider(ToolProviderBinding.of(provider, configuration));
        }

        public Builder toolProvider(ToolProviderBinding binding)
        {
            toolProviders.add(requireNonNull(binding, "binding is null"));
            return this;
        }

        /** Installs a fresh scoped contributor for every session, including text-only sessions. */
        public <C> Builder promptProvider(PromptProvider<C> provider, C configuration)
        {
            return promptProvider(PromptProviderBinding.of(provider, configuration));
        }

        public Builder promptProvider(PromptProviderBinding binding)
        {
            promptProviders.add(requireNonNull(binding, "binding is null"));
            return this;
        }

        /** Installs a fresh session-owned restrictive policy and/or metadata observer. */
        public <C> Builder extension(EngineExtensionProvider<C> provider, C configuration)
        {
            return extension(EngineExtensionBinding.of(provider, configuration));
        }

        public Builder extension(EngineExtensionBinding binding)
        {
            extensions.add(requireNonNull(binding, "binding is null"));
            return this;
        }

        /** Borrows a shared admission budget; the engine never closes the host service. */
        public Builder toolExecutionBudget(ToolExecutionBudget budget)
        {
            toolExecutionBudget = Optional.of(requireNonNull(budget, "toolExecutionBudget is null"));
            return this;
        }

        /** Opts into a bounded asynchronous batch window; values greater than one require a shared budget. */
        public Builder maximumParallelTools(int value)
        {
            checkArgument(value >= 1 && value <= 64, "parallel tool window must be between 1 and 64");
            maximumParallelTools = value;
            return this;
        }

        /** Borrows an opt-in summary policy; no hidden inference or tool work is permitted. */
        public Builder contextCompactor(ContextCompactor compactor)
        {
            contextCompactor = Optional.of(requireNonNull(compactor, "contextCompactor is null"));
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
            contextSummarization = Optional.of(requireNonNull(policy, "contextSummarization is null"));
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
            if (value <= 0) {
                throw new IllegalArgumentException("summary input limit must be positive");
            }
            maximumSummaryInputCharacters = value;
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
            auditRetention = Optional.of(requireNonNull(policy, "auditRetention is null"));
            return this;
        }

        public ClaudeApiConfig build() { return new ClaudeApiConfig(this); }
    }
}

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
import io.github.shiftinglabs.codej.cli.spi.context.ContextBudget;
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactor;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionBinding;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionProvider;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextSizer;
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
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * Immutable direct API configuration; no environment credentials or CLI settings are inferred.
 * Settings are validated when built, so a builder may be filled in any order.
 */
public final class ApiConfig
{
    /** A direct inference API: its connector id, default endpoint, built-in context estimates and wire adapter. */
    public enum Provider
    {
        CLAUDE("claude-api", "https://api.anthropic.com", BuiltinContextSizer.CLAUDE, AnthropicModelClient::new),
        DEEPSEEK("deepseek-api", "https://api.deepseek.com", BuiltinContextSizer.DEEPSEEK, DeepSeekModelClient::new);

        private final String id;
        private final URI baseUrl;
        private final ModelContextSizer sizer;
        private final Function<ApiConfig, ApiModelClient> clients;

        Provider(String id, String baseUrl, ModelContextSizer sizer, Function<ApiConfig, ApiModelClient> clients)
        {
            this.id = id;
            this.baseUrl = URI.create(baseUrl);
            this.sizer = sizer;
            this.clients = clients;
        }

        /** The connector id, also the default state folder name. */
        public String id() { return id; }

        ApiModelClient client(ApiConfig config) { return clients.apply(config); }
    }

    final Provider provider;
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
    final Optional<ContextBudget> contextBudget;
    final Optional<ModelContextSizer> contextSizer;

    private ApiConfig(Builder builder)
    {
        provider = builder.provider;
        model = checkText(requireNonNull(builder.model, "model is required"), "model");
        checkArgument(model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"), "invalid model identifier");
        key = requireNonNull(builder.key, "apiKey is required");
        baseUrl = ApiHttpTransport.checkBaseUrl(builder.baseUrl);
        timeout = builder.timeout;
        checkArgument(timeout.isPositive() && timeout.compareTo(Duration.ofHours(1)) <= 0, "invalid API timeout");
        maximumOutputTokens = builder.maximumOutputTokens;
        checkArgument(maximumOutputTokens > 0, "maximumOutputTokens must be positive");
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
        contextBudget = builder.contextBudget;
        contextSizer = builder.contextSizer.or(() -> contextBudget.map(ignored -> provider.sizer));
        checkArgument(contextCompactor.isEmpty() || contextSummarization.isEmpty(), "choose local compaction or engine summarization");
        checkArgument(maximumParallelTools == 1 || toolExecutionBudget.isPresent(), "parallel tools require a shared execution budget");
    }

    public static Builder builder(Provider provider) { return new Builder(provider); }

    public static final class Builder
    {
        private final Provider provider;
        private String model;
        private Supplier<String> key;
        private URI baseUrl;
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
        private Optional<ContextBudget> contextBudget = Optional.empty();
        private Optional<ModelContextSizer> contextSizer = Optional.empty();

        private Builder(Provider provider)
        {
            this.provider = requireNonNull(provider, "provider is null");
            baseUrl = provider.baseUrl;
        }

        /** Sets the required model identifier; no model release is inferred. */
        public Builder model(String value)
        {
            model = requireNonNull(value, "model is null");
            return this;
        }

        /** Sets the required credential supplier, called afresh for each HTTP operation. */
        public Builder apiKey(Supplier<String> value)
        {
            key = requireNonNull(value, "apiKey is null");
            return this;
        }

        public Builder baseUrl(URI value)
        {
            baseUrl = requireNonNull(value, "baseUrl is null");
            return this;
        }

        public Builder requestTimeout(Duration value)
        {
            timeout = requireNonNull(value, "requestTimeout is null");
            return this;
        }

        public Builder maximumOutputTokens(int value)
        {
            maximumOutputTokens = value;
            return this;
        }

        public Builder callbackExecutor(Executor value)
        {
            callbacks = Optional.of(requireNonNull(value, "callbackExecutor is null"));
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

        /** Borrows a policy enabling bounded tool-free summary inferences; cannot be combined with a compactor. */
        public Builder contextSummarization(ContextSummarizationPolicy policy)
        {
            contextSummarization = Optional.of(requireNonNull(policy, "contextSummarization is null"));
            return this;
        }

        /** Bounds each encoded summary input in UTF-16 characters; not a token estimate. */
        public Builder maximumSummaryInputCharacters(int value)
        {
            checkArgument(value > 0, "summary input limit must be positive");
            maximumSummaryInputCharacters = value;
            return this;
        }

        /** Borrows an opt-in audit retention policy; covered history is irreversibly removed. */
        public Builder auditRetention(AuditRetentionPolicy policy)
        {
            auditRetention = Optional.of(requireNonNull(policy, "auditRetention is null"));
            return this;
        }

        /** Enables preflight of actual input plus reserved output; built-in estimates apply unless a sizer is installed. */
        public Builder contextBudget(ContextBudget budget)
        {
            contextBudget = Optional.of(requireNonNull(budget, "contextBudget is null"));
            return this;
        }

        /** Borrows a local sizing service that overrides built-in estimates even when it returns unknown. */
        public Builder contextSizer(ModelContextSizer sizer)
        {
            contextSizer = Optional.of(requireNonNull(sizer, "contextSizer is null"));
            return this;
        }

        public ApiConfig build() { return new ApiConfig(this); }
    }
}

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

import io.github.shiftinglabs.codej.cli.spi.AgentConnector;
import io.github.shiftinglabs.codej.cli.spi.context.ContextCompactor;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineExtensionBinding;
import io.github.shiftinglabs.codej.cli.spi.prompt.PromptProviderBinding;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolProviderBinding;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestApiConnector;
import io.github.shiftinglabs.codej.sdk.testing.ConfiguredExtensions;
import io.github.shiftinglabs.codej.sdk.testing.ConfiguredLookupTools;
import io.github.shiftinglabs.codej.sdk.testing.ConfiguredPrompts;
import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekApiConnector
        extends AbstractTestApiConnector<DeepSeekApiConfig>
{
    private DeepSeekHttpFixture http;

    @Test
    void summaryPoliciesAreOptionalBorrowedAndRejectConflictingCompactors()
    {
        ContextSummarizationPolicy firstPolicy = context -> { throw new AssertionError("No eager callback"); };
        ContextSummarizationPolicy secondPolicy = context -> Optional.empty();
        var builder = DeepSeekApiConfig.builder("model", () -> "key");
        assertThat(builder.build().contextSummarization).isEmpty();
        var first = builder.contextSummarization(firstPolicy).build();
        builder.contextSummarization(secondPolicy);
        assertThat(first.contextSummarization).containsSame(firstPolicy);
        assertThat(builder.build().contextSummarization).containsSame(secondPolicy);
        assertThatThrownBy(() -> builder.contextSummarization(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.contextCompactor(context -> Optional.empty()).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contextCompactorIsBorrowedOptionalAndCapturedWithoutCallingIt()
    {
        ContextCompactor firstPolicy = context -> { throw new AssertionError("No eager compaction"); };
        ContextCompactor secondPolicy = context -> java.util.Optional.empty();
        var builder = DeepSeekApiConfig.builder("model", () -> "key");
        assertThat(builder.build().contextCompactor).isEmpty();
        var first = builder.contextCompactor(firstPolicy).build();
        builder.contextCompactor(secondPolicy);
        assertThat(first.contextCompactor).containsSame(firstPolicy);
        assertThat(builder.build().contextCompactor).containsSame(secondPolicy);
        assertThatThrownBy(() -> builder.contextCompactor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void executionBudgetsAreBorrowedOptionalAndSnapshotBuilderState()
    {
        var firstBudget = ToolExecutionBudget.bounded(1);
        var secondBudget = ToolExecutionBudget.bounded(2);
        var builder = DeepSeekApiConfig.builder("model", () -> "key");
        assertThat(builder.build().toolExecutionBudget).isEmpty();
        assertThat(builder.build().maximumParallelTools).isEqualTo(1);
        assertThatThrownBy(() -> builder.maximumParallelTools(2).build()).isInstanceOf(IllegalArgumentException.class);
        builder.maximumParallelTools(1);
        assertThatThrownBy(() -> builder.maximumParallelTools(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.maximumParallelTools(65)).isInstanceOf(IllegalArgumentException.class);
        var first = builder.toolExecutionBudget(firstBudget).build();
        builder.toolExecutionBudget(secondBudget).maximumParallelTools(2);
        assertThat(first.maximumParallelTools).isEqualTo(1);
        assertThat(builder.build().maximumParallelTools).isEqualTo(2);
        assertThat(first.toolExecutionBudget).containsSame(firstBudget);
        assertThat(builder.build().toolExecutionBudget).containsSame(secondBudget);
        assertThatThrownBy(() -> builder.toolExecutionBudget(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void extensionBindingsAreTypedImmutableAndRejectDuplicateIds()
    {
        var provider = new ConfiguredExtensions("policy");
        var binding = EngineExtensionBinding.of(provider, true);
        var builder = DeepSeekApiConfig.builder("model", () -> "key").extension(binding);
        var first = builder.build();
        builder.extension(new ConfiguredExtensions("other"), false);
        assertThat(first.extensions).containsExactly(binding);
        assertThat(builder.build().extensions).hasSize(2);
        assertThat(provider.opened).hasValue(0);
        assertThatThrownBy(() -> builder.extension((EngineExtensionBinding) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.extension(provider, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.extension(provider, false).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promptBindingsAreTypedSnapshotBuilderStateAndRejectDuplicateIds()
    {
        var provider = new ConfiguredPrompts("project");
        var binding = PromptProviderBinding.of(provider, "first");
        var builder = DeepSeekApiConfig.builder("model", () -> "key").promptProvider(binding);
        var first = builder.build();
        builder.promptProvider(new ConfiguredPrompts("second"), "next");
        assertThat(first.promptProviders).containsExactly(binding);
        assertThat(builder.build().promptProviders).hasSize(2);
        assertThat(provider.opened).hasValue(0);
        assertThatThrownBy(() -> builder.promptProvider((PromptProviderBinding) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.promptProvider(provider, "duplicate").build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerConfigurationBindingsAreExplicitAndSnapshotBuilderState()
    {
        var provider = new ConfiguredLookupTools();
        var binding = ToolProviderBinding.of(provider, new ConfiguredLookupTools.Configuration("configured:"));
        var builder = DeepSeekApiConfig.builder("model", () -> "key").toolProvider(binding);
        var first = builder.build();
        builder.toolProvider(provider, new ConfiguredLookupTools.Configuration("second:"));
        assertThat(first.toolProviders).containsExactly(binding);
        assertThat(builder.build().toolProviders).hasSize(2);
        assertThat(provider.opened).hasValue(0);
        assertThatThrownBy(() -> builder.toolProvider((ToolProviderBinding) null)).isInstanceOf(NullPointerException.class);
    }

    @BeforeEach
    void setup()
            throws Exception
    {
        http = new DeepSeekHttpFixture();
    }

    @AfterEach
    void cleanup() { http.close(); }

    @Override
    protected AgentConnector<DeepSeekApiConfig> connector() { return new DeepSeekApiConnector(); }

    @Override
    protected DeepSeekApiConfig configuration()
    {
        return DeepSeekApiConfig.builder(DeepSeekHttpFixture.MODEL, () -> "sk-test-contract").baseUrl(http.baseUrl()).build();
    }
}

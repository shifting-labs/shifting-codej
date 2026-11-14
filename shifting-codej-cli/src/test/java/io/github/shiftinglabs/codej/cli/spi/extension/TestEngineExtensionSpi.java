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
package io.github.shiftinglabs.codej.cli.spi.extension;

import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestEngineExtensionSpi
{
    @Test
    void bindingsCaptureIdsWithoutOpeningAndNeverDescribeConfiguration()
    {
        var opens = new AtomicInteger();
        EngineExtensionProvider<String> provider = provider("project", (setting, context) -> {
            opens.incrementAndGet();
            return new EngineExtension() {};
        });
        EngineExtensionBinding binding = EngineExtensionBinding.of(provider, "private-key");
        assertThat(binding.id()).isEqualTo("project");
        assertThat(binding.toString()).doesNotContain("private-key");
        assertThat(opens).hasValue(0);
        assertThatThrownBy(() -> binding.open(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EngineExtensionBinding.of(null, "value")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EngineExtensionBinding.of(provider, null)).isInstanceOf(NullPointerException.class);
        for (String id : List.of("", "bad/id", "line\nbreak", "a".repeat(65))) {
            assertThatThrownBy(() -> EngineExtensionBinding.of(provider(id, (setting, context) -> null), "value")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> EngineExtensionBinding.of(provider(null, (setting, context) -> null), "value")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rosterSnapshotsRejectShadowingAndRetainTheirOriginalOrder()
    {
        EngineExtensionBinding first = EngineExtensionBinding.of(provider("first", (setting, context) -> new EngineExtension() {}), "one");
        EngineExtensionBinding second = EngineExtensionBinding.of(provider("second", (setting, context) -> new EngineExtension() {}), "two");
        var values = new ArrayList<>(List.of(first, second));
        List<EngineExtensionBinding> snapshot = EngineExtensionBinding.snapshot(values);
        values.clear();
        assertThat(snapshot).containsExactly(first, second);
        assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EngineExtensionBinding.snapshot(List.of(first, first))).isInstanceOf(IllegalArgumentException.class);
        var many = java.util.stream.IntStream.range(0, 64 + 1)
                .mapToObj(i -> EngineExtensionBinding.of(provider("p" + i, (setting, context) -> new EngineExtension() {}), "value")).toList();
        assertThatThrownBy(() -> EngineExtensionBinding.snapshot(many)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineExtensionBinding.snapshot(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EngineExtensionBinding.snapshot(Arrays.asList((EngineExtensionBinding) null))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void observationRecordsRejectMalformedValuesAndDefaultPortsAreNeutral()
    {
        var extension = new EngineExtension() {};
        ToolPolicy policy = extension;
        EngineObserver observer = extension;
        assertThat(policy.evaluate(null)).isEqualTo(ToolPolicy.Decision.CONTINUE);
        observer.observe(new EngineObservation.TurnStarted("turn"));
        extension.close();
        assertThatThrownBy(() -> new EngineObservation.TurnStarted(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineObservation.ModelCompleted("turn", "request", null, java.util.Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineObservation.ModelCompleted("turn", "request", io.github.shiftinglabs.codej.cli.spi.model.ModelResponse.StopReason.COMPLETED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineObservation.ToolDecision("turn", "call", "tool", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineObservation.ToolCompleted("turn", "", "tool", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineObservation.TurnFinished("turn", null, java.time.Duration.ZERO)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineObservation.TurnFinished("turn", io.github.shiftinglabs.codej.cli.driver.AgentTurnResult.State.COMPLETED,
                java.time.Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryObservationsExposeOnlyValidatedMetadata()
    {
        var value = new EngineObservation.ContextSummaryCompleted("turn", "request", ModelResponse.StopReason.COMPLETED, Optional.empty());
        assertThat(value.turnId()).isEqualTo("turn");
        assertThat(value.requestId()).isEqualTo("request");
        assertThat(value.stopReason()).isEqualTo(ModelResponse.StopReason.COMPLETED);
        assertThat(value.usage()).isEmpty();
        assertThatThrownBy(() -> new EngineObservation.ContextSummaryCompleted(" ", "request", ModelResponse.StopReason.COMPLETED, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineObservation.ContextSummaryCompleted("turn", " ", ModelResponse.StopReason.COMPLETED, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineObservation.ContextSummaryCompleted("turn", "request", null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineObservation.ContextSummaryCompleted("turn", "request", ModelResponse.StopReason.COMPLETED, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static EngineExtensionProvider<String> provider(String id, java.util.function.BiFunction<String, EngineExtensionContext, EngineExtension> factory)
    {
        return new EngineExtensionProvider<>()
        {
            @Override
            public String id() { return id; }

            @Override
            public EngineExtension open(String setting, EngineExtensionContext context) { return factory.apply(setting, context); }
        };
    }
}

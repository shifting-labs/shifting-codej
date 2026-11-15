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
package io.github.shiftinglabs.codej.cli.spi.prompt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPromptSpiValues
{
    @Test
    void sectionsAndContributionsAreImmutableBoundedAndSafeToDescribe()
    {
        var section = new PromptSection("project.rules", "Private rules");
        var supplied = new ArrayList<>(List.of(section));
        var value = new PromptContribution(supplied, List.of(new PromptSection("facts", "Private context")));
        supplied.clear();
        assertThat(section.id()).isEqualTo("project.rules");
        assertThat(section.text()).isEqualTo("Private rules");
        assertThat(value.instructions()).containsExactly(section);
        assertThat(value.context().getFirst().text()).isEqualTo("Private context");
        assertThat(PromptContribution.empty()).isEqualTo(new PromptContribution(List.of(), List.of()));
        assertThat(section.toString()).doesNotContain("Private rules");
        assertThat(value.toString()).doesNotContain("Private rules", "Private context");
        assertThatThrownBy(() -> value.instructions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PromptContribution(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PromptContribution(List.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PromptContribution(Arrays.asList((PromptSection) null), List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void malformedSectionNamesTextDuplicatesAndOversizedBatchesFailBeforeRendering()
    {
        for (String id : List.of("", " ", "../escape", "line\nbreak", "a".repeat(129))) {
            assertThatThrownBy(() -> new PromptSection(id, "text")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PromptSection(null, "text")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PromptSection("id", null)).isInstanceOf(NullPointerException.class);
        for (String text : List.of("", " ", "x".repeat(PromptContribution.MAX_CHARACTERS + 1))) {
            assertThatThrownBy(() -> new PromptSection("id", text)).isInstanceOf(IllegalArgumentException.class);
        }
        var section = new PromptSection("same", "text");
        assertThatThrownBy(() -> new PromptContribution(List.of(section), List.of(section))).isInstanceOf(IllegalArgumentException.class);
        var many = java.util.stream.IntStream.range(0, PromptContribution.MAX_SECTIONS + 1).mapToObj(i -> new PromptSection("s" + i, "x")).toList();
        assertThatThrownBy(() -> new PromptContribution(many, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromptContribution(List.of(new PromptSection("a", "x".repeat(40000))),
                List.of(new PromptSection("b", "x".repeat(40000))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindingsCaptureIdsWithoutOpeningAndNeverDescribeConfiguration()
    {
        var opens = new AtomicInteger();
        PromptProvider<String> provider = provider("project", (setting, context) -> {
            opens.incrementAndGet();
            return request -> PromptContribution.empty();
        });
        PromptProviderBinding binding = PromptProviderBinding.of(provider, "private-key");
        assertThat(binding.id()).isEqualTo("project");
        assertThat(binding.toString()).doesNotContain("private-key");
        assertThat(opens).hasValue(0);
        assertThatThrownBy(() -> binding.open(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PromptProviderBinding.of(null, "value")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PromptProviderBinding.of(provider, null)).isInstanceOf(NullPointerException.class);
        for (String id : List.of("", "bad/id", "line\nbreak", "a".repeat(65))) {
            assertThatThrownBy(() -> PromptProviderBinding.of(provider(id, (setting, context) -> null), "value")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PromptProviderBinding.of(provider(null, (setting, context) -> null), "value")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rosterSnapshotsRejectShadowingAndRetainTheirOriginalOrder()
    {
        PromptProviderBinding first = PromptProviderBinding.of(provider("first", (setting, context) -> request -> PromptContribution.empty()), "one");
        PromptProviderBinding second = PromptProviderBinding.of(provider("second", (setting, context) -> request -> PromptContribution.empty()), "two");
        var values = new ArrayList<>(List.of(first, second));
        List<PromptProviderBinding> snapshot = PromptProviderBinding.snapshot(values);
        values.clear();
        assertThat(snapshot).containsExactly(first, second);
        assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> PromptProviderBinding.snapshot(List.of(first, first))).isInstanceOf(IllegalArgumentException.class);
        var many = java.util.stream.IntStream.range(0, PromptContribution.MAX_SECTIONS + 1)
                .mapToObj(i -> PromptProviderBinding.of(provider("p" + i, (setting, context) -> request -> PromptContribution.empty()), "value")).toList();
        assertThatThrownBy(() -> PromptProviderBinding.snapshot(many)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromptProviderBinding.snapshot(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PromptProviderBinding.snapshot(Arrays.asList((PromptProviderBinding) null))).isInstanceOf(NullPointerException.class);
    }

    private static PromptProvider<String> provider(String id, java.util.function.BiFunction<String, PromptSessionContext, PromptContributor> factory)
    {
        return new PromptProvider<>()
        {
            @Override
            public String id() { return id; }

            @Override
            public PromptContributor open(String setting, PromptSessionContext context) { return factory.apply(setting, context); }
        };
    }
}

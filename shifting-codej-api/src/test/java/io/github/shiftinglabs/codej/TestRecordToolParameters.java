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

import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRecordToolParameters
{
    enum Kind { FIRST, SECOND }

    record Child(String value) {}

    record Input(int count, boolean enabled, Kind kind, List<Child> children, Optional<String> note) {}

    record Count(int count) {}

    interface Parameters { int count(); }

    record Values(int count) implements Parameters {}

    @Test
    void strictCodecChecksSchemaBeforeBindingNestedOptionalAndEnumValues()
    {
        var codec = RecordToolParameters.of(Input.class);
        String valid = """
                {"count":2,"enabled":true,"kind":"SECOND","children":[{"value":"hello"}]}
                """;
        assertThat(codec.decode(ToolArguments.of(valid))).isEqualTo(new Input(2, true, Kind.SECOND, List.of(new Child("hello")), Optional.empty()));
        for (String invalid : List.of("{}", valid.replace("2", "\"2\""), valid.replace("true", "1"), valid.replace("SECOND", "UNKNOWN"),
                valid.replace("{\"value\":\"hello\"}", "{}"), valid.replace("2", "null"), valid.strip() + " {}")) {
            assertThatThrownBy(() -> codec.decode(() -> invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(codec.schema().json()).isEqualTo(Tool.of("example", "Example", Input.class, input -> "ok").inputSchema());
        assertThatThrownBy(() -> RecordToolParameters.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void oldRecordFactoryRetainsDirectBindingAndNullSourceCompatibility()
            throws Exception
    {
        Tool legacy = Tool.of("count", "Count", Count.class, value -> Integer.toString(value.count()));
        // Keep the original Jackson scalar coercion and missing-primitive error behavior for direct callers.
        assertThatThrownBy(() -> legacy.call("{}")).isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Invalid arguments:");
        assertThat(legacy.call("{\"count\":\"2\"}")).isEqualTo("2");
        assertThatThrownBy(() -> RecordToolParameters.of(Count.class).decode(() -> "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tool.of("count", "Count", null, input -> "ok")).isInstanceOf(NullPointerException.class).hasMessage("arguments is null");
    }

    @Test
    void interfaceFactoryBindsOnlyAtCallAndRetainsSubagentFlags()
            throws Exception
    {
        AtomicInteger bindings = new AtomicInteger();
        var records = RecordToolParameters.of(Count.class);
        ToolParameters<Parameters> codec = new ToolParameters<>()
        {
            @Override
            public io.github.shiftinglabs.codej.tool.ToolSchema schema() { return records.schema(); }

            @Override
            public Parameters decode(ToolArguments arguments)
            {
                bindings.incrementAndGet();
                return new Values(records.decode(arguments).count());
            }
        };
        Tool tool = Tool.withParameters("count", "Count", codec, value -> Integer.toString(value.count()));
        assertThat(bindings).hasValue(0);
        assertThat(tool.subagents()).isFalse();
        assertThat(tool.forSubagents().call("{\"count\":3}")).isEqualTo("3");
        assertThat(bindings).hasValue(1);
        assertThat(tool.forSubagents().subagents()).isTrue();
        assertThat(tool.forSubagents().inputSchema()).isEqualTo(tool.inputSchema());
        assertThatThrownBy(() -> Tool.withParameters("count", "Count", null, input -> "ok")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Tool.withParameters("count", "Count", codec, null)).isInstanceOf(NullPointerException.class);
    }
}

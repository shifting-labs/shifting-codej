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
package io.github.shiftinglabs.codej.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestJsonSchemaParameters
{
    @Test
    void retainsLegacyEnumsAdditionalPropertiesAndWireSpelling()
    {
        String schema = """
                {"type":"object","properties":{"kind":{"enum":[1,"two",null]}},"required":["kind"],"additionalProperties":{"type":"boolean"}}
                """;
        AtomicInteger calls = new AtomicInteger();
        var codec = JsonSchemaParameters.of(schema, arguments -> { calls.incrementAndGet(); return arguments.json(); });
        assertThat(codec.schema().json()).isEqualTo(schema);
        for (String valid : List.of("{\"kind\":1}", "{\"kind\":\"two\",\"other\":true}", "{\"kind\":null}")) {
            assertThat(codec.decode(() -> valid)).isEqualTo(valid);
        }
        assertThat(calls).hasValue(3);
        for (String invalid : List.of("{}", "[]", "null", "{\"kind\":3}", "{\"kind\":1,\"other\":2}", "{\"kind\":1,\"kind\":1}", "{\"kind\":1} {}")) {
            assertThatThrownBy(() -> codec.decode(() -> invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).hasValue(3);
        assertThat(codec.schema()).isEqualTo(JsonSchemaParameters.of(schema, arguments -> arguments).schema());
    }

    @Test
    void alternateEnvelopesRetainHostLimitsBeyondConvenienceDefaults()
    {
        String description = "x".repeat(70000);
        String schema = "{\"description\":\"" + description + "\",\"properties\":{\"text\":{\"type\":\"string\"}}}";
        String input = "{\"text\":\"" + description + "\"}";
        var codec = JsonSchemaParameters.of(schema, ToolArguments::json);
        assertThat(codec.decode(() -> input)).isEqualTo(input);
        assertThat(codec.schema().json()).isEqualTo(schema);
        assertThat(ToolDescriptor.builder("large", description, Integer.MAX_VALUE).build().description()).isEqualTo(description);
        assertThatThrownBy(() -> ToolDescriptor.builder("large", description)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolDescriptor.builder("large", "valid", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidAssertionsFailAtConstructionAndCustomFormatsOwnValidation()
    {
        for (String invalid : List.of("[]", "{} {}", "{\"minimum\":1}", "{\"type\":[]}", "{\"required\":[1]}", "{\"additionalProperties\":null}",
                "{\"properties\":[]}", "{\"enum\":[]}", "{\"items\":{\"unknown\":1}}")) {
            assertThatThrownBy(() -> JsonSchemaParameters.of(invalid, ToolArguments::json)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> JsonSchemaParameters.of("{}", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JsonSchemaParameters.of("{}", input -> null).decode(() -> "{}")).isInstanceOf(NullPointerException.class);
        AtomicInteger checks = new AtomicInteger();
        ToolSchemaFormat format = json -> { checks.incrementAndGet(); return json.strip(); };
        assertThat(ToolSchema.of(" {} ", format)).isEqualTo(ToolSchema.of("{}", format));
        assertThat(checks).hasValue(2);
        assertThatThrownBy(() -> ToolSchema.of("{}", value -> null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolSchema.of("{}", null)).isInstanceOf(NullPointerException.class);
    }
}

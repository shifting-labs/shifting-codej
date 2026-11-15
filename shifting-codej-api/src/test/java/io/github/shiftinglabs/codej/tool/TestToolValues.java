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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolValues
{
    @Test
    void envelopesAreBoundedStrictAndHideTheirData()
    {
        String json = " {\"secret\":\"private-value\",\"nested\":{\"list\":[1,true,null]}} ";
        ToolArguments input = ToolArguments.of(json);
        assertThat(input.json()).isEqualTo(json);
        assertThat(input.toString()).doesNotContain("private-value");
        for (String invalid : List.of("", "null", "[]", "true", "{} {}", "{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{\"nested\":{\"x\":1,\"x\":2}}")) {
            assertThatThrownBy(() -> ToolArguments.of(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ToolArguments.of("{\"x\":\"" + "x".repeat(65536) + "\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolArguments.of("{\"x\":\"" + "界".repeat(22000) + "\"}")).isInstanceOf(IllegalArgumentException.class);
        String deep = "{\"x\":".repeat(65) + "0" + "}".repeat(65);
        assertThatThrownBy(() -> ToolArguments.of(deep)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolArguments.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void schemaFingerprintsNormalizeOrderingWithoutChangingWireSpelling()
    {
        String first = """
                {"type":"object","required":["b","a"],"properties":{"b":{"type":"boolean"},"a":{"type":"string","enum":["y","x"]}},"additionalProperties":false}
                """;
        String second = """
                {"properties":{"a":{"enum":["x","y"],"type":"string"},"b":{"type":"boolean"}},"required":["a","b"],"additionalProperties":false,"type":"object"}
                """;
        ToolSchema a = ToolSchema.of(first);
        ToolSchema b = ToolSchema.of(second);
        assertThat(a.json()).isEqualTo(first);
        assertThat(a.fingerprint()).matches("[a-f0-9]{64}").isEqualTo(b.fingerprint());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(ToolSchema.of("{}"));
        assertThat(a).isNotEqualTo(null).isNotEqualTo("schema");
        assertThat(a.toString()).contains(a.fingerprint()).doesNotContain("properties");
    }

    @Test
    void schemasSupportRecordShapesAndRejectUnsupportedOrMalformedAssertions()
    {
        assertThat(ToolSchema.of("{}").json()).isEqualTo("{}");
        assertThat(ToolSchema.of("""
                {"type":"object","title":"Input","description":"A record","properties":{"items":{"type":"array","items":{"type":"integer"}},
                 "weight":{"type":"number"},"empty":{"type":"null"}},"additionalProperties":false}
                """).fingerprint()).hasSize(64);
        for (String invalid : List.of("[]", "{} {}", "{\"type\":\"string\"}", "{\"type\":\"array\"}", "{\"type\":\"unknown\"}", "{\"minimum\":1}", "{\"oneOf\":[]}",
                "{\"properties\":{\"x\":{\"format\":\"date\"}}}", "{\"required\":[\"x\",\"x\"]}", "{\"enum\":[]}",
                "{\"enum\":[\"x\",\"x\"]}", "{\"additionalProperties\":{}}", "{\"additionalProperties\":\"false\"}",
                "{\"properties\":null}", "{\"required\":null}", "{\"type\":null}", "{\"description\":12}", "{\"enum\":[1]}")) {
            assertThatThrownBy(() -> ToolSchema.of(invalid)).as(invalid).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void outputsHaveExplicitFormatsAndBounds()
    {
        ToolOutput text = ToolOutput.text("private-value");
        assertThat(text.text()).isEqualTo("private-value");
        assertThat(text.mediaType()).isEqualTo("text/plain");
        assertThat(text.toString()).doesNotContain("private-value");
        ToolOutput json = ToolOutput.json("{\"ok\":true}");
        assertThat(json.text()).isEqualTo("{\"ok\":true}");
        assertThat(json.mediaType()).isEqualTo("application/json");
        assertThat(json.toString()).doesNotContain("true");
        assertThat(ToolOutput.text("").text()).isEmpty();
        assertThatThrownBy(() -> ToolOutput.text("x".repeat(65537))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolOutput.text(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolOutput.json("[]")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descriptorsSnapshotBuilderStateAndKeepContractVersionsSeparateFromImplementations()
    {
        ToolDescriptor.Builder builder = ToolDescriptor.builder("read_file", "Read one file");
        ToolDescriptor first = builder.build();
        ToolDescriptor second = builder.contractVersion(2).implementationRevision("fix-123").build();
        assertThat(first.name()).isEqualTo("read_file");
        assertThat(first.description()).isEqualTo("Read one file");
        assertThat(first.contractVersion()).isEqualTo(1);
        assertThat(first.implementationRevision()).isEmpty();
        assertThat(second.contractVersion()).isEqualTo(2);
        assertThat(second.implementationRevision()).contains("fix-123");
        assertThatThrownBy(() -> ToolDescriptor.builder("Bad Name", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolDescriptor.builder("ok", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.contractVersion(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.implementationRevision(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void definitionsUseBindingInterfacesWithoutRecordOrClassRestrictions()
    {
        ToolSchema schema = ToolSchema.of("{\"type\":\"object\",\"additionalProperties\":false}");
        ToolParameters<Parameters> parameters = new ToolParameters<>()
        {
            @Override
            public ToolSchema schema() { return schema; }

            @Override
            public Parameters decode(ToolArguments arguments)
            {
                if (!arguments.json().strip().matches("\\{\\s*}")) {
                    throw new IllegalArgumentException("Expected empty arguments");
                }
                return new Values("value");
            }
        };
        ToolResultCodec<Parameters> results = value -> ToolOutput.text(value.value());
        ToolDescriptor descriptor = ToolDescriptor.builder("example", "An example").build();
        ToolDefinition<Parameters, Parameters> definition = ToolDefinitions.of(descriptor, parameters, results);
        assertThat(definition.descriptor()).isSameAs(descriptor);
        assertThat(definition.parameters()).isSameAs(parameters);
        assertThat(definition.results()).isSameAs(results);
        assertThat(definition.toString()).contains("example").doesNotContain("Values", "parameters=");
        assertThat(definition.results().encode(definition.parameters().decode(ToolArguments.of("{ }"))).text()).isEqualTo("value");
        assertThatThrownBy(() -> ToolDefinitions.of(null, parameters, results)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolDefinitions.of(descriptor, null, results)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolDefinitions.of(descriptor, parameters, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void transportAndOutputCanUseAlternateImplementations()
    {
        ToolArguments input = new AlternateArguments(ToolArguments.of("{}"));
        ToolOutput output = new AlternateOutput(ToolOutput.json("{\"ok\":true}"));
        assertThat(input.json()).isEqualTo("{}");
        assertThat(input.toString()).doesNotContain("{}");
        assertThat(output.text()).isEqualTo("{\"ok\":true}");
        assertThat(output.mediaType()).isEqualTo("application/json");
    }

    private record AlternateArguments(ToolArguments value)
            implements ToolArguments
    {
        @Override
        public String json() { return value.json(); }

        @Override
        public String toString() { return "AlternateArguments[redacted]"; }
    }

    private record AlternateOutput(ToolOutput value)
            implements ToolOutput
    {
        @Override
        public String text() { return value.text(); }

        @Override
        public String mediaType() { return value.mediaType(); }
    }

    private interface Parameters { String value(); }

    private record Values(String value)
            implements Parameters {}
}

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
package io.github.shiftinglabs.codej.cli.claude;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestClaudeToolOutput
{
    private static final String RECEIPT = "{\"turnId\":\"turn-1\",\"report\":{\"readyForDelivery\":true,\"message\":\"done known-secret\"}}";

    private final ObjectMapper json = new ObjectMapper().registerModule(new Jdk8Module()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void aCompletedHostToolCallCarriesWhatItReturned()
            throws Exception
    {
        ClaudeEventMapper mapper = mapper();
        assertThat(start(mapper, "mcp__codej__report_development_result", "{\"readyForDelivery\":true}", false))
                .filteredOn(ProviderAgentEvent.Tool.class::isInstance).allSatisfy(event -> assertThat(((ProviderAgentEvent.Tool) event).output()).isEmpty());

        assertThat(output(finish(mapper, false, false))).contains(new ProviderAgentEvent.ToolOutput("report_development_result",
                "{\"turnId\":\"turn-1\",\"report\":{\"readyForDelivery\":true,\"message\":\"done [REDACTED]\"}}"));
    }

    @Test
    void aDispatchedCallNamesTheToolItRan()
            throws Exception
    {
        ClaudeEventMapper mapper = mapper();
        start(mapper, "mcp__codej__codej_call_tool", "{\"name\":\"report_development_result\",\"arguments\":{\"readyForDelivery\":true}}", false);

        assertThat(output(finish(mapper, false, false))).map(ProviderAgentEvent.ToolOutput::tool).contains("report_development_result");
    }

    @Test
    void failedForeignAndUnprovenCallsCarryNothing()
            throws Exception
    {
        ClaudeEventMapper failed = mapper();
        start(failed, "mcp__codej__report_development_result", "{}", false);
        assertThat(output(finish(failed, false, true))).isEmpty();

        ClaudeEventMapper foreign = mapper();
        start(foreign, "mcp__other__report_development_result", "{}", false);
        assertThat(output(finish(foreign, false, false))).isEmpty();

        // A child whose agent was never seen would otherwise read as the root.
        ClaudeEventMapper unknownChild = mapper();
        start(unknownChild, "mcp__codej__report_development_result", "{}", true);
        assertThat(output(finish(unknownChild, true, false))).isEmpty();

        ClaudeEventMapper elsewhere = mapper();
        start(elsewhere, "mcp__codej__report_development_result", "{}", false);
        assertThat(output(finish(elsewhere, true, false))).isEmpty();
    }

    private ClaudeEventMapper mapper()
    {
        return new ClaudeEventMapper(SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret")), ToolServer.DEFAULT, true);
    }

    private List<ProviderAgentEvent> start(ClaudeEventMapper mapper, String name, String input, boolean child)
    {
        return mapper.map(ClaudeProtocol.decode(json, """
                {"type":"assistant","session_id":"session-1","uuid":"start","parent_tool_use_id":%s,
                "message":{"role":"assistant","content":[{"type":"tool_use","id":"tool-1","name":"%s","input":%s}]}}
                """.formatted(child ? "\"parent-1\"" : "null", name, input)));
    }

    private List<ProviderAgentEvent> finish(ClaudeEventMapper mapper, boolean child, boolean failed)
            throws Exception
    {
        return mapper.map(ClaudeProtocol.decode(json, """
                {"type":"user","session_id":"session-1","uuid":"finish","parent_tool_use_id":%s,
                "message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool-1","is_error":%s,"content":%s}]}}
                """.formatted(child ? "\"parent-1\"" : "null", failed, json.writeValueAsString(ImmutableList.of(ImmutableMap.of("type", "text", "text", RECEIPT))))));
    }

    private static Optional<ProviderAgentEvent.ToolOutput> output(List<ProviderAgentEvent> events)
    {
        return events.stream().filter(ProviderAgentEvent.Tool.class::isInstance).map(ProviderAgentEvent.Tool.class::cast)
                .flatMap(tool -> tool.output().stream()).findFirst();
    }
}

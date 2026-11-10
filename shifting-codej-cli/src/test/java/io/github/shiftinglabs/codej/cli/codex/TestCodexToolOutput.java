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
package io.github.shiftinglabs.codej.cli.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexToolOutput
{
    private static final String RECEIPT = "{\"turnId\":\"app-turn\",\"report\":{\"readyForDelivery\":true,\"message\":\"done known-secret\"}}";

    private final ObjectMapper json = new ObjectMapper().registerModule(new Jdk8Module());
    private final CodexProtocolCodec codec = new CodexProtocolCodec(json);
    private final CodexEventMapper mapper = new CodexEventMapper(
            ToolServer.DEFAULT, Path.of("/tmp"), SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret")), true);

    @Test
    void aCompletedHostToolCallCarriesWhatItReturned()
            throws Exception
    {
        ProviderAgentEvent.Tool completed = tool(notification("item/completed", "completed", "null", "codej", "false"));

        assertThat(completed.output()).contains(new ProviderAgentEvent.ToolOutput("report_development_result",
                "{\"turnId\":\"app-turn\",\"report\":{\"readyForDelivery\":true,\"message\":\"done [REDACTED]\"}}"));
        assertThat(completed.source().providerThreadId()).contains("root-thread");
        assertThat(completed.source().providerTurnId()).contains("native-turn");
        assertThat(completed.source().providerItemId()).contains("tool-1");
    }

    @Test
    void startedFailedForeignAndErrorResultsCarryNothing()
            throws Exception
    {
        for (CodexProtocol.Notification notification : ImmutableList.of(
                notification("item/started", "inProgress", "null", "codej", "false"),
                notification("item/completed", "failed", "null", "codej", "false"),
                notification("item/completed", "completed", "{\"message\":\"failed\"}", "codej", "false"),
                notification("item/completed", "completed", "null", "other", "false"),
                notification("item/completed", "completed", "null", "codej", "true"))) {
            assertThat(tool(notification).output()).isEmpty();
        }
    }

    @Test
    void aDispatchedCallNamesTheToolItRan()
            throws Exception
    {
        for (String type : ImmutableList.of("mcpToolCall", "dynamicToolCall")) {
            ObjectNode event = json.valueToTree(notification("item/completed", "completed", "null", "codej", "false"));
            ObjectNode tool = (ObjectNode) event.at("/params/item");
            tool.put("type", type).put("tool", "codej_call_tool");
            tool.putObject("arguments").put("name", "report_development_result").putObject("arguments").put("readyForDelivery", true);
            tool.put("success", true);
            tool.putArray("contentItems").addObject().put("type", "inputText").put("text", RECEIPT);
            assertThat(tool(codec.decode(event.toString(), CodexProtocol.Notification.class)).output()).as(type)
                    .map(ProviderAgentEvent.ToolOutput::tool).contains("report_development_result");
            tool.put("status", "failed").put("success", false);
            assertThat(tool(codec.decode(event.toString(), CodexProtocol.Notification.class)).output()).as(type).isEmpty();
        }
    }

    private ProviderAgentEvent.Tool tool(CodexProtocol.Notification notification)
    {
        return mapper.map(notification, true, CodexEventMapper.ThreadLineage.NONE).stream()
                .filter(ProviderAgentEvent.Tool.class::isInstance).map(ProviderAgentEvent.Tool.class::cast).findFirst().orElseThrow();
    }

    private CodexProtocol.Notification notification(String method, String status, String error, String server, String isError)
            throws Exception
    {
        return codec.decode("""
                {"method":"%s","params":{"threadId":"root-thread","turnId":"native-turn","item":{
                "type":"mcpToolCall","id":"tool-1","server":"%s","tool":"report_development_result",
                "arguments":{},"status":"%s","error":%s,"result":{"content":[{"type":"text","text":%s}],"isError":%s}}}}
                """.formatted(method, server, status, error, json.writeValueAsString(RECEIPT), isError), CodexProtocol.Notification.class);
    }
}

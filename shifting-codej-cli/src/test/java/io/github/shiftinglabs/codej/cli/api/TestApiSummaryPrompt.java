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
package io.github.shiftinglabs.codej.cli.api;

import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiSummaryPrompt
{
    @Test
    void transcriptKeepsToolDataAndAttachmentMetadataWithoutPathsOrFileAccess()
    {
        var attachment = new AgentAttachment("image.png", "image/png", Path.of("/private/missing/secret-image.png"));
        var history = new ModelHistory(true, List.of(
                new ModelHistory.Exchange("one", "fake", "model", new ModelInput.UserMessage("goal \"quoted\"", List.of(attachment)),
                        "looking", List.of(new ModelResponse.ToolCall("call", "lookup", "{\"query\":\"needle\"}")), ModelResponse.StopReason.TOOL_CALLS),
                new ModelHistory.Exchange("two", "fake", "model", new ModelInput.ToolResults(List.of(new ModelInput.ToolResult("call", "found", false))),
                        "decision", List.of(), ModelResponse.StopReason.COMPLETED),
                new ModelHistory.Exchange("three", "fake", "model", new ModelInput.UserMessage("retained", List.of()),
                        "recent", List.of(), ModelResponse.StopReason.COMPLETED)));
        assertThat(ApiSummaryPrompt.encode(history, 2, 4000)).contains("goal", "quoted", "image.png", "image/png", "lookup", "needle", "tool_results", "found", "decision")
                .doesNotContain("/private/missing", "secret-image", "retained", "recent");
    }

    @Test
    void escapedTranscriptIsBoundedBeforeInference()
    {
        var history = new ModelHistory(true, List.of(new ModelHistory.Exchange("one", "fake", "model",
                new ModelInput.UserMessage("\"".repeat(1000), List.of()), "reply", List.of(), ModelResponse.StopReason.COMPLETED)));
        assertThatThrownBy(() -> ApiSummaryPrompt.encode(history, 1, 1500)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("reply");
        assertThat(ApiSummaryPrompt.encode(history, 1, 3000)).hasSizeLessThan(3000);
    }
}

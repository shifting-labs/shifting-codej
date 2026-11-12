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
package io.github.shiftinglabs.codej.cli.spi.model;

import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** New input since a replay checkpoint; tool results stay distinct from user text and instructions. */
public sealed interface ModelInput
        permits ModelInput.UserMessage, ModelInput.ToolResults
{
    record UserMessage(String text, List<AgentAttachment> attachments)
            implements ModelInput
    {
        public UserMessage
        {
            requireNonNull(text, "text is null");
            attachments = List.copyOf(attachments);
            checkArgument(!text.isBlank() || !attachments.isEmpty(), "a user message needs text or attachments");
        }
    }

    /** Complete batch answering the pending client tool calls, in their original order. */
    record ToolResults(List<ToolResult> results)
            implements ModelInput
    {
        public ToolResults
        {
            results = List.copyOf(results);
            checkArgument(!results.isEmpty(), "tool results are empty");
            checkArgument(results.stream().map(ToolResult::callId).distinct().count() == results.size(), "duplicate tool result ID");
        }
    }

    /** One host tool outcome; error text is supplied to the model as a failed tool result. */
    record ToolResult(String callId, String text, boolean error)
    {
        public ToolResult
        {
            checkText(callId, "callId");
            requireNonNull(text, "text is null");
        }
    }
}

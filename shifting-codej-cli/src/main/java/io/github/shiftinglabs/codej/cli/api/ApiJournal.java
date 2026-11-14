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

import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;

import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Engine execution facts; provider-native replay is deliberately a separate private value. */
final class ApiJournal
{
    private ApiJournal() {}

    sealed interface Entry permits User, Request, Response, ToolStarted, ToolResult, CodingLaunch, PreparedResource {}

    record User(String turn, ModelInput.UserMessage input) implements Entry
    {
        User
        {
            checkText(turn, "turn");
            requireNonNull(input, "input is null");
        }

        @Override
        public String toString()
        {
            return "User[redacted]";
        }
    }

    record Request(String id) implements Entry
    {
        Request
        {
            checkText(id, "id");
        }
    }

    record Response(String id, ModelResponse.StopReason stop) implements Entry
    {
        Response
        {
            checkText(id, "id");
            requireNonNull(stop, "stop is null");
        }
    }

    record ToolStarted(String id) implements Entry
    {
        ToolStarted
        {
            checkText(id, "id");
        }
    }

    record ToolResult(String id, String text, boolean error) implements Entry
    {
        ToolResult
        {
            checkText(id, "id");
            requireNonNull(text, "text is null");
        }

        @Override
        public String toString()
        {
            return "ToolResult[error=" + error + ", redacted]";
        }
    }

    record CodingLaunch(String launchId) implements Entry
    {
        CodingLaunch
        {
            checkText(launchId, "launchId");
        }
    }

    record PreparedResource(String id, ToolResource resource) implements Entry
    {
        PreparedResource
        {
            checkText(id, "id");
            requireNonNull(resource, "resource is null");
        }
    }
}

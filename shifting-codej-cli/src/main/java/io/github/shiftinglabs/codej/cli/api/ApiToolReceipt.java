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

import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;

import java.util.Optional;

import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** A durable tool intent and its observed outcome; absence of an outcome never proves no effect. */
record ApiToolReceipt(String id, String name, String arguments, Optional<String> eventId, boolean started,
        Optional<ToolRegistration.Result> result)
{
    ApiToolReceipt
    {
        new ModelResponse.ToolCall(id, name, arguments);
        requireNonNull(eventId, "eventId is null").ifPresent(value -> checkText(value, "eventId"));
        requireNonNull(result, "result is null");
    }

    static ApiToolReceipt pending(String requestId, ModelResponse.ToolCall call)
    {
        checkText(requestId, "requestId");
        return new ApiToolReceipt(call.id(), call.name(), call.arguments(), Optional.of(requestId + ":" + call.id()), false, Optional.empty());
    }

    ApiToolReceipt markStarted() { return new ApiToolReceipt(id, name, arguments, eventId, true, result); }

    ApiToolReceipt completed(String text, boolean error)
    {
        return new ApiToolReceipt(id, name, arguments, eventId, started, Optional.of(new ToolRegistration.Result(text, error)));
    }

    ToolDispatcher.Call invocation() { return new ToolDispatcher.Call(id, name, arguments, eventId.orElse(null)); }

    boolean uncertain() { return started && result.isEmpty(); }

    @Override
    public String toString() { return "ApiToolReceipt[started=" + started + ", completed=" + result.isPresent() + ", redacted]"; }
}

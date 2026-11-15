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
package io.github.shiftinglabs.codej.cli.spi.tool;

import java.time.Instant;
import java.util.concurrent.CancellationException;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Invocation-scoped authority and durable hooks; no global service locator or model client. */
public interface ToolExecutionContext
        extends ToolPreparationContext
{
    /** Effective authority captured after host policy and approval; tool declarations never grant it. */
    ToolRequirements grants();

    boolean cancellationRequested();

    /** Must persist synchronously before an external resource can start effects; failure prevents launch. */
    void recordPrepared(ToolResource resource);

    /** Optional sanitized progress; implementations apply their own budget and redaction before delivery. */
    default void progress(String message)
    {
        checkArgument(!requireNonNull(message, "message is null").isBlank() && message.length() <= 1024, "invalid progress message");
    }

    default void checkActive()
    {
        if (cancellationRequested() || deadline().filter(value -> !value.isAfter(Instant.now())).isPresent()) {
            throw new CancellationException("Tool execution cancelled or deadline exceeded");
        }
    }
}

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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Sanitized failure data. Implementations must redact secrets before constructing it. */
public record ToolFailure(String code, String message)
{
    public ToolFailure
    {
        checkArgument(requireNonNull(code, "code is null").matches("[A-Z][A-Z0-9_]{0,63}"), "invalid failure code");
        checkArgument(!requireNonNull(message, "message is null").isBlank() && message.length() <= 1024, "invalid failure message");
    }
}

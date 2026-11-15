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

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Positive shutdown proof or a sanitized diagnostic explaining why ownership must remain held. */
public record ToolStopResult(boolean stopped, Optional<String> diagnostic)
{
    public ToolStopResult
    {
        requireNonNull(diagnostic, "diagnostic is null").ifPresent(value ->
                checkArgument(!value.isBlank() && value.length() <= 1024, "invalid stop diagnostic"));
        checkArgument(stopped == diagnostic.isEmpty(), "unverified stop requires a diagnostic; verified stop has none");
    }

    public static ToolStopResult verifiedStopped() { return new ToolStopResult(true, Optional.empty()); }

    public static ToolStopResult unverified(String diagnostic) { return new ToolStopResult(false, Optional.of(requireNonNull(diagnostic, "diagnostic is null"))); }
}

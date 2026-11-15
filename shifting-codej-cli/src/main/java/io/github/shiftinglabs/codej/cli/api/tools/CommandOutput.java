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
package io.github.shiftinglabs.codej.cli.api.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable bounded output snapshot; completion and verified shutdown are separate lifecycle facts. */
public record CommandOutput(String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated)
{
    public CommandOutput
    {
        checkArgument(requireNonNull(stdout, "stdout is null").length() <= 65536, "stdout exceeds snapshot limit");
        checkArgument(requireNonNull(stderr, "stderr is null").length() <= 65536, "stderr exceeds snapshot limit");
    }

    @Override
    public String toString() { return "CommandOutput[redacted, stdoutTruncated=" + stdoutTruncated + ", stderrTruncated=" + stderrTruncated + "]"; }
}

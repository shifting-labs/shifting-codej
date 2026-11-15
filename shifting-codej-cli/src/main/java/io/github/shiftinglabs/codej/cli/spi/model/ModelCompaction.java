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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Explicit lossy context proposal; summary is user-role data, never instructions or a tool result. */
public record ModelCompaction(String summary, int retainExchanges)
{
    public static final int MAX_SUMMARY_CHARACTERS = 65536;

    public ModelCompaction
    {
        requireNonNull(summary, "summary is null");
        checkArgument(!summary.isBlank() && summary.length() <= MAX_SUMMARY_CHARACTERS, "invalid compaction summary");
        checkArgument(retainExchanges >= 0, "negative retained exchange count");
    }

    @Override
    public String toString() { return "ModelCompaction[retainExchanges=" + retainExchanges + ", summary redacted]"; }
}

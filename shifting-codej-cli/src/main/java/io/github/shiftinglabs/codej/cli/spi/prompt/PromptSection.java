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
package io.github.shiftinglabs.codej.cli.spi.prompt;

import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One named section. Text is private prompt data; its source label never grants authority. */
public record PromptSection(String id, String text)
{
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");

    public PromptSection
    {
        checkArgument(ID.matcher(requireNonNull(id, "id is null")).matches(), "invalid prompt section id");
        checkArgument(!requireNonNull(text, "text is null").isBlank() && text.length() <= PromptContribution.MAX_CHARACTERS, "invalid prompt section text");
    }

    @Override
    public String toString() { return "PromptSection[" + id + ", text redacted]"; }
}

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
package io.github.shiftinglabs.codej;

import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What you send in a turn: text and, optionally, files such as screenshots.
 *
 * @param text the message; not blank
 * @param attachments files the agent can see with the message
 */
public record Input(String text, List<Attachment> attachments)
{
    /**
     * Creates an input; copies {@code attachments}.
     *
     * @throws IllegalArgumentException when {@code text} is blank
     */
    public Input
    {
        requireNonNull(text, "text is null");
        checkArgument(!text.isBlank(), "text is blank");
        attachments = ImmutableList.copyOf(attachments);
    }

    /**
     * A text-only input.
     *
     * @param text the message; not blank
     * @return the input
     * @throws IllegalArgumentException when {@code text} is blank
     */
    public static Input text(String text)
    {
        return new Input(text, ImmutableList.of());
    }

    /**
     * This input with one more file the agent can see.
     *
     * @param file an absolute path to the file
     * @param mediaType its media type, one of the agent's {@link Capabilities#attachmentTypes()}
     * @return a new input
     * @throws IllegalArgumentException when {@code file} is not absolute
     */
    public Input withAttachment(Path file, String mediaType)
    {
        return new Input(text, ImmutableList.<Attachment>builder().addAll(attachments).add(new Attachment(file, mediaType)).build());
    }

    /**
     * A file sent with a message.
     *
     * @param file an absolute path to the file
     * @param mediaType its media type, such as {@code image/png}
     */
    public record Attachment(Path file, String mediaType)
    {
        /**
         * Creates an attachment.
         *
         * @throws IllegalArgumentException when {@code file} is not absolute or {@code mediaType} is blank
         */
        public Attachment
        {
            requireNonNull(file, "file is null");
            checkArgument(file.isAbsolute(), "file is not absolute: %s", file);
            requireNonNull(mediaType, "mediaType is null");
            checkArgument(!mediaType.isBlank(), "mediaType is blank");
        }
    }
}

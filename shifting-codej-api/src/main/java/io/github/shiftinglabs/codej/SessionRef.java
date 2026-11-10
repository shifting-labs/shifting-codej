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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Identifies a conversation so it can be resumed later. Opaque: store {@link #asString()}, read it
 * back with {@link #parse(String)}, then resume it with an agent of the same provider and account.
 *
 * @param provider the provider that holds the conversation, such as {@code codex}
 * @param id the provider's own conversation id
 */
public record SessionRef(String provider, String id)
{
    /**
     * Creates a ref.
     *
     * @throws IllegalArgumentException when either part is blank or {@code provider} contains a colon
     */
    public SessionRef
    {
        requireNonNull(provider, "provider is null");
        requireNonNull(id, "id is null");
        checkArgument(!provider.isBlank() && !provider.contains(":"), "Invalid provider: %s", provider);
        checkArgument(!id.isBlank(), "id is blank");
    }

    /**
     * A stable text form of this ref.
     *
     * @return text that {@link #parse(String)} reads back
     */
    public String asString()
    {
        return provider + ":" + id;
    }

    /**
     * Reads a ref stored with {@link #asString()}.
     *
     * @param text the stored text
     * @return the ref
     * @throws IllegalArgumentException when {@code text} is not a session ref
     */
    public static SessionRef parse(String text)
    {
        int colon = text.indexOf(':');
        checkArgument(colon >= 0, "Not a session ref: %s", text);
        return new SessionRef(text.substring(0, colon), text.substring(colon + 1));
    }
}

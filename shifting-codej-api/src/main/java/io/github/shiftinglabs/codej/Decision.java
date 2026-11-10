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

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * An answer to an {@link Approval}.
 *
 * @param kind what the answer is
 * @param reason why a request is denied, told to the agent so it can do something else; only a denial has one
 */
public record Decision(Kind kind, Optional<String> reason)
{
    /** Allow this request only. */
    public static final Decision ALLOW_ONCE = new Decision(Kind.ALLOW_ONCE, Optional.empty());
    /** Allow this request and matching later ones in the same session, as the provider scopes them, such as one command prefix. */
    public static final Decision ALLOW_FOR_SESSION = new Decision(Kind.ALLOW_FOR_SESSION, Optional.empty());
    /** Refuse it. The agent is told and carries on without it. */
    public static final Decision DENY = new Decision(Kind.DENY, Optional.empty());

    /**
     * Creates a decision.
     *
     * @throws IllegalArgumentException when a decision other than a denial has a reason, or the reason is blank
     */
    public Decision
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(reason, "reason is null");
        checkArgument(reason.isEmpty() || kind == Kind.DENY, "Only a denial has a reason");
        checkArgument(reason.stream().noneMatch(String::isBlank), "The reason is blank");
    }

    /**
     * Refuses a request and tells the agent why.
     *
     * @param reason what the agent should know, such as what to do instead
     * @return the denial
     * @throws IllegalArgumentException when {@code reason} is blank
     */
    public static Decision deny(String reason)
    {
        return new Decision(Kind.DENY, Optional.of(reason));
    }

    /** What a decision is. */
    public enum Kind
    {
        /** Allow this request only. */
        ALLOW_ONCE,
        /** Allow this request and matching later ones in the same session. */
        ALLOW_FOR_SESSION,
        /** Refuse it. */
        DENY
    }
}

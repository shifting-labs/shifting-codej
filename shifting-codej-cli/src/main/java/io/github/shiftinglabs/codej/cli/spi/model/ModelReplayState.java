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

/**
 * Lossless provider-owned conversation checkpoint, including any content/signatures required for
 * replay. Only its adapter interprets the payload. The engine persists it privately and must not
 * redact or reconstruct it from public events. Never includes transport credentials.
 *
 * <p>The surrounding session store binds this checkpoint to its connector and profile; providerId
 * alone is not an authorization or profile boundary. There is no implicit cross-provider migration.
 */
public final class ModelReplayState
{
    private final String providerId;
    private final int formatVersion;
    private final String payload;

    public ModelReplayState(String providerId, int formatVersion, String payload)
    {
        this.providerId = requireNonNull(providerId, "providerId is null");
        checkArgument(providerId.matches("[a-z0-9]+(?:-[a-z0-9]+)*"), "invalid providerId");
        checkArgument(formatVersion > 0, "formatVersion must be positive");
        this.formatVersion = formatVersion;
        this.payload = requireNonNull(payload, "payload is null");
    }

    public String providerId() { return providerId; }

    public int formatVersion() { return formatVersion; }

    /** Sensitive replay data; never log or expose through an event sink. */
    public String payload() { return payload; }

    @Override
    public String toString()
    {
        return "ModelReplayState[providerId=" + providerId + ", formatVersion=" + formatVersion + ", payload=<private>]";
    }
}

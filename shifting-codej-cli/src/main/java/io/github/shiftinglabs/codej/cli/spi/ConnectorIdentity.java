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
package io.github.shiftinglabs.codej.cli.spi;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Stable, nonsecret identifiers for a complete runtime and one of its configured profiles.
 * Neither value may contain a credential or be derived from one; key rotation keeps this identity.
 * This metadata does not change the existing lane or session-reference serialization.
 *
 * @param connectorId lowercase letters and digits with single hyphens, such as {@code claude-code}
 * @param profileId a stable explicit profile ID: letters and digits followed by letters, digits,
 *                  periods, underscores or hyphens; case is preserved
 */
public record ConnectorIdentity(String connectorId, String profileId)
{
    public ConnectorIdentity
    {
        requireNonNull(connectorId, "connectorId is null");
        requireNonNull(profileId, "profileId is null");
        checkArgument(connectorId.matches("[a-z0-9]+(?:-[a-z0-9]+)*"), "invalid connectorId");
        checkArgument(profileId.matches("[A-Za-z0-9][A-Za-z0-9._-]*"), "invalid profileId");
    }
}

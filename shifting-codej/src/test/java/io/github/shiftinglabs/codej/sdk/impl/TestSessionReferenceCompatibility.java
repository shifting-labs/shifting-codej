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
package io.github.shiftinglabs.codej.sdk.impl;

import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestSessionReferenceCompatibility
{
    @Test
    void persistedReferencesKeepTheProviderNumericContractAndExactNativeValue()
    {
        for (String provider : List.of("codex", "claude")) {
            for (AgentLaneId lane : List.of(new AgentLaneId(provider), new AgentLaneId(provider + "-account-old-profile"))) {
                for (int contract : List.of(1, 3, Integer.MAX_VALUE)) {
                    for (String nativeValue : List.of("session", "thread:with:colons", "thread:", ":", "会話:é:β", " native:value ")) {
                        AgentSessionRef original = new AgentSessionRef(lane, contract, nativeValue);
                        SessionRef reference = DriverSession.ref(provider, original);
                        String stored = reference.asString();

                        assertThat(stored).isEqualTo(provider + ":" + contract + ":" + nativeValue);
                        // Downstream consumers parse the first two separators themselves.
                        String[] parts = stored.split(":", 3);
                        assertThat(parts).hasSize(3);
                        assertThat(parts[0]).isEqualTo(provider);
                        assertThat(Integer.parseInt(parts[1])).isEqualTo(contract);
                        assertThat(parts[2]).isEqualTo(nativeValue);
                        assertThat(DriverSession.reference(lane, provider, SessionRef.parse(stored))).isEqualTo(original);
                    }
                }
            }
        }
    }

    @Test
    void theAccountLaneComesFromTheSelectedDriverRatherThanTheStoredProvider()
    {
        for (String provider : List.of("codex", "claude")) {
            AgentLaneId account = new AgentLaneId(provider + "-account-old-profile");
            SessionRef stored = SessionRef.parse(provider + ":3:native:conversation");

            assertThat(DriverSession.reference(account, provider, stored))
                    .isEqualTo(new AgentSessionRef(account, 3, "native:conversation"));
            assertThat(DriverSession.ref(provider, new AgentSessionRef(account, 3, "native:conversation")).provider()).isEqualTo(provider);
        }
    }

    @Test
    void malformedStoredReferencesCannotResume()
    {
        for (String provider : List.of("codex", "claude")) {
            AgentLaneId lane = new AgentLaneId(provider);
            for (String id : List.of("", "native", ":native", "contract:native", "0:native", "-1:native", "2147483648:native", "3:", "3: \t")) {
                assertThatThrownBy(() -> DriverSession.reference(lane, provider, SessionRef.parse(provider + ":" + id)))
                        .as("invalid %s reference: %s", provider, id)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> SessionRef.parse(":3:native")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionRef.parse("codex")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStoredReferenceCannotChangeProviders()
    {
        assertThatThrownBy(() -> DriverSession.reference(new AgentLaneId("codex-account-old-profile"), "codex", SessionRef.parse("claude:3:native")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DriverSession.reference(new AgentLaneId("claude-account-old-profile"), "claude", SessionRef.parse("codex:3:native")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

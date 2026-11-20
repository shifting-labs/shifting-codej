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

import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.Antigravity;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestCliConnector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAntigravityConnector
        extends AbstractTestCliConnector<AntigravityConnectorConfig>
{
    @Override
    protected AntigravityConnector connector()
    {
        return new AntigravityConnector();
    }

    @Override
    protected AntigravityConnectorConfig configuration(Path executable)
    {
        return new AntigravityConnectorConfig(Optional.of(executable), Optional.empty());
    }

    @Test
    void buildsWithAPrivateProfileAndDefersCredentialsUntilLaunch()
            throws IOException
    {
        Path state = contractDirectory().resolve("state");
        Path home = contractDirectory().resolve("profile");
        Executor callbacks = Runnable::run;
        try (DriverAgent agent = (DriverAgent) Antigravity.builder()
                .executable(contractDirectory().resolve("missing-agy"))
                .configDir(home)
                .stateDirectory(state)
                .apiKey(() -> { throw new AssertionError("Building an agent must not read the API key"); })
                .callbackExecutor(callbacks)
                .build()) {
            assertThat(agent.callbacks()).isSameAs(callbacks);
            assertThat(agent.provider()).isEqualTo("antigravity");
            assertThat(Files.getPosixFilePermissions(home)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(state.resolve("antigravity")).doesNotExist();
            assertThat(state.resolve("processes/intents")).isEmptyDirectory();
        }
    }

    @Test
    void rejectsLinkedProfilesWithoutChangingTheirTarget()
            throws IOException
    {
        ConnectorContext context = contractContext();
        Path target = Files.createDirectory(contractDirectory().resolve("other"));
        Files.createSymbolicLink(context.profileDirectory(), target);
        var permissions = Files.getPosixFilePermissions(target);
        assertThatThrownBy(() -> connector().create(configuration(), context)).isInstanceOf(AgentException.class);
        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(permissions);
    }

    @Test
    void recordsTheSelectedProfileInItsIdentity()
    {
        ConnectorContext first = contractContext();
        ConnectorContext second = new ConnectorContext(contractDirectory().resolve("another-profile"), first.processSupervisor(), first.toolServer());
        try (ConnectorDriver one = connector().create(configuration(), first);
                ConnectorDriver two = connector().create(configuration(), second)) {
            assertThat(one.id().value()).isEqualTo("antigravity");
            assertThat(one.identity()).isNotEqualTo(two.identity());
        }
    }
}

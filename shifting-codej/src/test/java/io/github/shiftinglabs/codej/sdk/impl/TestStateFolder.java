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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.cli.process.ManagedProcess;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.ProcessSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStateFolder
{
    @TempDir
    private Path root;
    @TempDir
    private Path checkout;

    @Test
    void oneProgramAtATimeHoldsAFolder()
            throws IOException
    {
        StateFolder held = new StateFolder(root);
        assertThatThrownBy(() -> new StateFolder(root))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("Another program is using the state folder");
        held.releaseForTest();
        new StateFolder(root).releaseForTest();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void theNextRunStopsWhatAnEarlierRunLeft()
            throws Exception
    {
        StateFolder crashed = new StateFolder(root);
        String unstarted = UUID.randomUUID().toString();
        String running = UUID.randomUUID().toString();
        crashed.sessionDirectory(unstarted, checkout);
        crashed.sessionDirectory(running, checkout);
        crashed.sessionDirectory(UUID.randomUUID().toString(), checkout);
        crashed.supervisor().prepare(spec(unstarted + "-1"));
        ManagedProcess left = crashed.supervisor().prepare(spec(running)).start((stream, bytes) -> {});
        assertThat(ProcessHandle.of(left.pid()).map(ProcessHandle::isAlive)).contains(true);
        // The program ends without stopping its process, as a crash would.
        crashed.releaseForTest();

        StateFolder next = new StateFolder(root);
        assertThat(ProcessHandle.of(left.pid()).filter(ProcessHandle::isAlive)).isEmpty();
        assertThat(next.unconfirmedDirectories()).isEmpty();
        assertThat(root.resolve("sessions")).isEmptyDirectory();
        assertThat(root.resolve("processes/intents")).isEmptyDirectory();
        next.releaseForTest();
    }

    @Test
    void aProcessThatCannotBeShownStoppedNamesItsSessionsDirectory()
            throws IOException
    {
        StateFolder folder = new StateFolder(root);
        String session = UUID.randomUUID().toString();
        folder.sessionDirectory(session, checkout);
        Stream<Optional<ProcessOwner>> owners = Stream.of(
                Optional.of(new ProcessOwner(ProcessOwner.Kind.CLAUDE_TURN, session + "-3")),
                Optional.of(new ProcessOwner(ProcessOwner.Kind.CLI_PROBE, "claude-cli")),
                Optional.of(new ProcessOwner(ProcessOwner.Kind.APP_SERVER, UUID.randomUUID().toString())),
                Optional.empty());
        assertThat(folder.directories(owners)).containsExactly(checkout);
        folder.releaseForTest();
    }

    private ProcessSpec spec(String owner)
    {
        List<String> argv = ImmutableList.of("/bin/sleep", "30");
        return new ProcessSpec(new ProcessOwner(ProcessOwner.Kind.APP_SERVER, owner), argv, checkout, ImmutableMap.of("PATH", "/usr/bin:/bin"),
                OptionalInt.of(1024), 1024, Duration.ZERO, Duration.ofSeconds(1));
    }
}

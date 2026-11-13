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
package io.github.shiftinglabs.codej.cli.api.tools;

import io.github.shiftinglabs.codej.CodingTools;
import io.github.shiftinglabs.codej.cli.process.LaunchIntentRecorder;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.impl.DefaultProcessSupervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.CommandResult;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ShellCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs(OS.MAC)
class TestSandboxCommand
{
    @TempDir
    Path directory;

    @Test
    void operatingSystemContainsReadsWritesLinksForksAndNetwork()
            throws Exception
    {
        Path root = Files.createDirectories(directory.resolve(".codex/work (quoted)"));
        Path outside = directory.resolve("outside");
        Files.writeString(outside, "private");
        Files.writeString(root.resolve("inside"), "workspace");
        Files.createLink(root.resolve("hard"), outside);
        Files.createSymbolicLink(root.resolve("alias"), outside);
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "private metadata");
        Path future = root.resolve("later/private-state");
        Recorder recorder = new Recorder();
        var supervisor = new DefaultProcessSupervisor(directory.resolve("launches"), recorder);
        try (WorkspaceFiles files = new WorkspaceFiles(root, CodingTools.defaults(), true, List.of(future));
                SandboxCommand command = new SandboxCommand(files, CodingTools.defaults(), supervisor, "native-test", id -> assertThat(recorder.launches).containsKey(id))) {
            command.check();
            command.committed();
            Files.createDirectories(future);
            Files.writeString(future.resolve("token"), "private future state");
            Files.createDirectories(root.resolve("later/.git"));
            Files.writeString(root.resolve("later/.git/config"), "private future metadata");
            CommandResult read = run(command, List.of("/bin/cat", "inside"));
            assertThat(read.exitCode()).isZero();
            assertThat(read.stdout()).isEqualTo("workspace");
            for (String path : List.of(outside.toString(), "/System/Volumes/Data" + outside, "alias", "hard", ".git/config", "later/.git/config",
                    "later/private-state/token", root.toRealPath().toString().replace("/private/var/", "/var/") + "/later/.git/config")) {
                CommandResult denied = run(command, List.of("/bin/cat", path));
                assertThat(denied.exitCode()).isNotZero();
                assertThat(denied.stdout()).doesNotContain("private");
            }
            for (String path : List.of(outside.toString(), root.resolve("inside").toString())) {
                assertThat(run(command, List.of("/bin/sh", "-c", "printf changed > \"$1\"", "codej", path)).exitCode()).isNotZero();
            }
            assertThat(run(command, List.of("/bin/sh", "-c", "exec /bin/ln \"$PWD/inside\" \"$HOME/alias\"")).exitCode()).isNotZero();
            assertThat(run(command, List.of("/bin/sh", "-c", "/bin/sleep 0.1 & wait")).exitCode()).isNotZero();
            try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                CommandResult network = run(command, List.of("/usr/bin/nc", "-z", "-w", "1", "127.0.0.1", Integer.toString(server.getLocalPort())));
                assertThat(network.exitCode()).isNotZero();
            }
            assertThat(Files.readString(outside)).isEqualTo("private");
            assertThat(Files.readString(root.resolve("inside"))).isEqualTo("workspace");
        }
        assertThat(recorder.launches).isEmpty();
    }

    @Test
    void commandsHaveBoundedOutputAndTime()
            throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("work"));
        CodingTools limits = new CodingTools(1024, 1024, 100, 1, 64);
        Recorder recorder = new Recorder();
        var supervisor = new DefaultProcessSupervisor(directory.resolve("launches"), recorder);
        try (WorkspaceFiles files = new WorkspaceFiles(root, limits, true, List.of());
                SandboxCommand command = new SandboxCommand(files, limits, supervisor, "native-bounds", id -> {})) {
            CommandResult output = run(command, List.of("/bin/sh", "-c", "printf '%0256d' 1"));
            assertThat(output.stdout().length()).isLessThanOrEqualTo(64);
            assertThat(output.stdoutTruncated()).isTrue();
            CommandResult timed = command.run(new ShellCommand(List.of("/bin/sleep", "5"), ".", 1));
            assertThat(timed.reason()).isEqualTo(ProcessExit.Reason.TIMED_OUT);
            command.committed();
        }
        assertThat(recorder.launches).isEmpty();
    }

    @Test
    void unverifiedShutdownRetainsWorkspaceAndDurableLaunch()
            throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("work"));
        var supervisor = new FakeCodingSupervisor();
        WorkspaceFiles files = new WorkspaceFiles(root, CodingTools.defaults(), true, List.of());
        SandboxCommand command = new SandboxCommand(files, CodingTools.defaults(), supervisor, "uncertain-test", id -> {});
        try {
            supervisor.verified = false;
            assertThatThrownBy(() -> command.run(new ShellCommand(List.of("/usr/bin/true"), ".", 1)))
                    .isInstanceOf(CodingToolException.class).extracting(failure -> ((CodingToolException) failure).uncertain()).isEqualTo(true);
            assertThatThrownBy(command::close).isInstanceOf(CodingToolException.class);
            assertThat(supervisor.acknowledged).isEmpty();
            assertThatThrownBy(() -> new WorkspaceFiles(root, CodingTools.defaults(), true, List.of())).isInstanceOf(RuntimeException.class);
        }
        finally {
            supervisor.verified = true;
            command.close();
            files.close();
        }
    }

    private static CommandResult run(SandboxCommand command, List<String> argv)
            throws Exception
    {
        CommandResult result = command.run(new ShellCommand(argv, ".", 1));
        command.committed();
        return result;
    }

    private static final class Recorder
            implements LaunchIntentRecorder
    {
        final Map<String, Boolean> launches = new ConcurrentHashMap<>();

        @Override
        public void prepared(String id, ProcessOwner owner, String executable, String digest) { launches.put(id, false); }

        @Override
        public void attempting(String id) { launches.put(id, true); }

        @Override
        public Map<String, Boolean> committedLaunches() { return Map.copyOf(launches); }

        @Override
        public void forget(String id) { launches.remove(id); }
    }
}

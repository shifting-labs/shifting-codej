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

import io.github.shiftinglabs.codej.cli.process.LaunchIntentRecorder;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.process.ProcessOwner;
import io.github.shiftinglabs.codej.cli.process.impl.DefaultProcessSupervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit Linux acceptance gate; never silently substitutes a fake backend or weaker limits. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "codej.test.nativeCommands", matches = "true")
class TestLinuxNativeCommands
{
    @TempDir
    Path directory;

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

    private static ToolMessages.CommandResult run(CommandToolProvider.Backend backend, CommandLimits limits, List<String> argv, int seconds)
            throws Exception
    {
        var execution = backend.startTree(new ToolMessages.ShellCommand(argv, ".", seconds), limits,
                new ResourceTestContext(AbstractTestCommandExecutor.grants()));
        var outcome = execution.completion().toCompletableFuture().get(seconds + 10L, TimeUnit.SECONDS);
        assertThat(outcome.value()).as("The requested native isolation must be available on this acceptance host").isPresent();
        assertThat(execution.stopAndVerify().toCompletableFuture().get(5, TimeUnit.SECONDS).stopped()).isTrue();
        execution.committed();
        return outcome.value().orElseThrow();
    }

    @Test
    void realCommandTreeHasReadOnlyExportAndPrivateBoundedScratch()
            throws Exception
    {
        var recorder = new Recorder();
        var supervisor = new DefaultProcessSupervisor(directory.resolve("launches"), recorder);
        var assembly = TestNativeCommandBackend.assembly(directory, supervisor);
        Files.writeString(assembly.session().worktree().resolve("inside"), "workspace");
        Files.createDirectory(assembly.session().worktree().resolve(".git"));
        Files.writeString(assembly.session().worktree().resolve(".git/config"), "private");
        var limits = CommandLimits.defaults();
        var config = BuiltinCommands.configuration(limits);
        try (var backend = config.factory().open(limits, assembly)) {
            var result = run(backend, limits, List.of("/bin/sh", "-c", "(/bin/cat inside) & wait; printf scratch > /tmp/probe; /bin/cat /tmp/probe"), 15);
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).isEqualTo("workspacescratch");
            assertThat(run(backend, limits, List.of("/bin/cat", ".git/config"), 15).exitCode()).isNotZero();
            assertThat(run(backend, limits, List.of("/bin/sh", "-c", "printf changed > inside"), 15).exitCode()).isNotZero();
        }
        assertThat(Files.readString(assembly.session().worktree().resolve("inside"))).isEqualTo("workspace");
        assertThat(recorder.launches).isEmpty();
    }

    @Test
    void realCommandOutputAndBackgroundChildrenRemainOwnedUntilStopped()
            throws Exception
    {
        var recorder = new Recorder();
        var supervisor = new DefaultProcessSupervisor(directory.resolve("launches"), recorder);
        var assembly = TestNativeCommandBackend.assembly(directory, supervisor);
        var limits = new CommandLimits(64, 100, 512, 8, 20, 64);
        var config = BuiltinCommands.configuration(limits);
        try (var backend = config.factory().open(limits, assembly)) {
            var output = run(backend, limits, List.of("/bin/sh", "-c", "printf '%0256d' 1; /usr/bin/setsid /bin/sleep 30 &"), 15);
            assertThat(output.stdout().length()).isLessThanOrEqualTo(64);
            assertThat(output.stdoutTruncated()).isTrue();
            var execution = backend.startTree(new ToolMessages.ShellCommand(List.of("/bin/sleep", "30"), ".", 15), limits,
                    new ResourceTestContext(AbstractTestCommandExecutor.grants()));
            execution.cancel();
            var outcome = execution.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertThat(outcome.value().orElseThrow().reason()).isEqualTo(ProcessExit.Reason.CANCELLED);
            assertThat(execution.stopAndVerify().toCompletableFuture().get(5, TimeUnit.SECONDS).stopped()).isTrue();
            execution.committed();
        }
        assertThat(recorder.launches).isEmpty();
    }
}

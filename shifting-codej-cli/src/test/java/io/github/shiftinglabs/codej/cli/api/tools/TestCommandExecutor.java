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
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs(OS.MAC)
class TestCommandExecutor
        extends AbstractTestCommandExecutor
{
    @TempDir
    Path directory;

    private final class Owned
            implements Fixture
    {
        final FakeCodingSupervisor supervisor = new FakeCodingSupervisor();
        final ResourceTestContext context = new ResourceTestContext(grants());
        final WorkspaceFiles files;
        final SandboxCommand command;

        Owned()
                throws Exception
        {
            Path root = Files.createTempDirectory(directory, "commands-");
            files = new WorkspaceFiles(root, CodingTools.defaults(), false, List.of());
            command = new SandboxCommand(files, CodingTools.defaults(), supervisor, "command-contract", id -> context.trace.add("owner-prepared"));
            supervisor.beforeStart = () -> context.trace.add("start");
        }

        @Override
        public CommandExecutor executor() { return command; }

        @Override
        public ResourceTestContext context() { return context; }

        @Override
        public int started() { return supervisor.started; }

        @Override
        public List<String> acknowledged() { return supervisor.acknowledged; }

        @Override
        public void close()
                throws Exception
        {
            command.close();
            files.close();
            assertThat(supervisor.closed).isFalse();
        }
    }

    @Override
    protected Fixture open()
            throws Exception
    {
        return new Owned();
    }

    @Test
    void unverifiedTerminationRetainsOwnershipAndNeverAcknowledgesAnUnknownEffect()
            throws Exception
    {
        try (Owned fixture = new Owned()) {
            try {
                fixture.supervisor.verified = false;
                var execution = fixture.command.start(request(), fixture.context);
                assertThat(execution.completion().toCompletableFuture().get().state()).isEqualTo(ToolOutcome.State.UNCERTAIN);
                execution.cancel();
                assertThat(fixture.supervisor.cancelled).containsExactly("launch-1");
                assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isFalse();
                assertThatThrownBy(execution::committed).isInstanceOf(CodingToolException.class);
                assertThatThrownBy(fixture.command::close).isInstanceOf(CodingToolException.class);
                assertThat(fixture.supervisor.acknowledged).isEmpty();
                assertThatThrownBy(() -> new WorkspaceFiles(fixture.files.root(), CodingTools.defaults(), true, List.of())).isInstanceOf(RuntimeException.class);
                fixture.supervisor.verified = true;
                assertThat(execution.stopAndVerify().toCompletableFuture().get(2, TimeUnit.SECONDS).stopped()).isTrue();
                assertThat(execution.completion().toCompletableFuture().get().state()).isEqualTo(ToolOutcome.State.UNCERTAIN);
                assertThatThrownBy(execution::committed).isInstanceOf(CodingToolException.class);
                assertThat(fixture.supervisor.acknowledged).isEmpty();
            }
            finally {
                fixture.supervisor.verified = true;
            }
        }
    }

    @Test
    void commandLimitsAndMissingDirectoryAreTypedFailuresBeforeLaunch()
            throws Exception
    {
        try (Owned fixture = new Owned()) {
            var missing = fixture.command.start(new ToolMessages.ShellCommand(List.of("/usr/bin/true"), "missing", 1), fixture.context);
            assertThat(missing.completion().toCompletableFuture().get().failure().orElseThrow().code()).isEqualTo("FILE_IO");
            var excessive = fixture.command.start(new ToolMessages.ShellCommand(List.of("/usr/bin/true"), ".", 300), fixture.context);
            assertThat(excessive.completion().toCompletableFuture().get().failure().orElseThrow().code()).isEqualTo("COMMAND_LIMIT");
            assertThat(fixture.supervisor.started).isZero();
            assertThat(missing.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
        }
    }

    @Test
    void uncommittedCommandCannotBeReplacedByAnotherHandle()
            throws Exception
    {
        try (Owned fixture = new Owned()) {
            var first = fixture.command.start(request(), fixture.context);
            var rejected = fixture.command.start(request(), fixture.context);
            assertThat(rejected.completion().toCompletableFuture().get().state()).isEqualTo(ToolOutcome.State.UNCERTAIN);
            assertThatThrownBy(rejected::committed).isInstanceOf(CodingToolException.class);
            assertThat(fixture.supervisor.started).isEqualTo(1);
            first.committed();
            assertThat(fixture.supervisor.acknowledged).containsExactly("launch-1");
        }
    }
}

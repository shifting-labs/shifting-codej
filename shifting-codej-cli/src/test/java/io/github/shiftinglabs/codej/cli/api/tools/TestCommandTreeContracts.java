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

import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.ToolArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCommandTreeContracts
{
    private static final ToolMessages.ShellCommand REQUEST = new ToolMessages.ShellCommand(List.of("/bin/sh", "-c", "printf hello"), ".", 1);

    @Test
    void legacyImplementationsRetainTheirSingleAbstractMethodAndRejectTreesBeforeEffects()
    {
        var starts = new AtomicInteger();
        CommandExecutor legacy = (request, context) -> {
            starts.incrementAndGet();
            throw new AssertionError("Legacy start must not be used for trees");
        };
        assertThat(legacy.supportsCommandTrees()).isFalse();
        var context = new ResourceTestContext(AbstractTestCommandExecutor.grants());
        assertThatThrownBy(() -> legacy.startTree(REQUEST, CommandLimits.defaults(), context)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RunCommandTool(legacy, CommandLimits.defaults())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> legacy.startTree(null, CommandLimits.defaults(), context)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> legacy.startTree(REQUEST, null, context)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> legacy.startTree(REQUEST, CommandLimits.defaults(), null)).isInstanceOf(NullPointerException.class);
        assertThat(starts).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"1,100,512,64,30,8192", "513,100,512,64,30,8192", "64,0,512,64,30,8192", "64,801,512,64,30,8192",
            "64,100,63,64,30,8192", "64,100,16385,64,30,8192", "64,100,512,0,30,8192", "64,100,512,1025,30,8192",
            "64,100,512,64,0,8192", "64,100,512,64,301,8192", "64,100,512,64,30,0", "64,100,512,64,30,65537"})
    void rejectsUnlimitedOrExcessiveHostBounds(int processes, int cpu, int memory, int scratch, int seconds, int output)
    {
        assertThatThrownBy(() -> new CommandLimits(processes, cpu, memory, scratch, seconds, output)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotsAreBoundedImmutableAndRedacted()
    {
        assertThat(CommandLimits.defaults()).isEqualTo(new CommandLimits(64, 100, 512, 64, 30, 8192));
        var output = new CommandOutput("private stdout", "private stderr", true, false);
        assertThat(output.stdout()).isEqualTo("private stdout");
        assertThat(output.stderr()).isEqualTo("private stderr");
        assertThat(output.stdoutTruncated()).isTrue();
        assertThat(output.stderrTruncated()).isFalse();
        assertThat(output.toString()).doesNotContain("private stdout", "private stderr");
        assertThatThrownBy(() -> new CommandOutput(null, "", false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommandOutput("", null, false, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommandOutput("x".repeat(65537), "", false, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandOutput("", "x".repeat(65537), false, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typedHandlerPreservesTheInvocationHandleAndHostBounds()
    {
        var backend = new Backend();
        var limits = CommandLimits.defaults();
        var tool = new RunCommandTool(backend, limits);
        var context = new ResourceTestContext(AbstractTestCommandExecutor.grants());
        var decoded = tool.definition().parameters().decode(ToolArguments.of("{\"argv\":[\"/bin/sh\",\"-c\",\"printf hello\"],\"directory\":\".\",\"timeoutSeconds\":1}"));
        assertThat(decoded).isEqualTo(REQUEST);
        assertThat(tool.definition().descriptor().name()).isEqualTo("run_command");
        assertThat(tool.requirements(decoded, context).permits(AbstractTestCommandExecutor.grants())).isTrue();
        assertThat(tool.execute(decoded, context)).isSameAs(backend.execution);
        assertThat(backend.request).isSameAs(decoded);
        assertThat(backend.limits).isSameAs(limits);
        assertThat(backend.context).isSameAs(context);
        assertThat(backend.starts).isEqualTo(1);
        assertThat(backend.execution.output()).isEqualTo(new CommandOutput("", "", false, false));
        assertThat(backend.execution.completion().toCompletableFuture()).isNotDone();
        String unauthorized = "{\"argv\":[\"/bin/true\"],\"directory\":\".\",\"timeoutSeconds\":1,\"maximumProcesses\":999}";
        assertThatThrownBy(() -> tool.definition().parameters().decode(ToolArguments.of(unauthorized)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorityDeadlineCancellationAndTimeoutAreCheckedBeforeBackendEffects()
    {
        var backend = new Backend();
        var tool = new RunCommandTool(backend, CommandLimits.defaults());
        var context = new ResourceTestContext(ToolRequirements.none());
        assertThatThrownBy(() -> tool.execute(REQUEST, context)).isInstanceOf(SecurityException.class);
        context.grants = AbstractTestCommandExecutor.grants();
        context.cancelled = true;
        assertThatThrownBy(() -> tool.execute(REQUEST, context)).isInstanceOf(CancellationException.class);
        context.cancelled = false;
        context.deadline = Optional.of(Instant.EPOCH);
        assertThatThrownBy(() -> tool.execute(REQUEST, context)).isInstanceOf(CancellationException.class);
        context.deadline = Optional.empty();
        var excessive = new ToolMessages.ShellCommand(List.of("/bin/true"), ".", 31);
        assertThatThrownBy(() -> tool.execute(excessive, context)).isInstanceOf(IllegalArgumentException.class);
        assertThat(backend.starts).isZero();
    }

    private static final class Backend
            implements CommandExecutor
    {
        final Execution execution = new Execution();
        int starts;
        ToolMessages.ShellCommand request;
        CommandLimits limits;
        ToolExecutionContext context;

        @Override
        public boolean supportsCommandTrees() { return true; }

        @Override
        public ToolExecution<ToolMessages.CommandResult> start(ToolMessages.ShellCommand request, ToolExecutionContext context)
        {
            throw new AssertionError("Legacy path must not be used");
        }

        @Override
        public CommandExecution startTree(ToolMessages.ShellCommand request, CommandLimits limits, ToolExecutionContext context)
        {
            starts++;
            this.request = request;
            this.limits = limits;
            this.context = context;
            return execution;
        }
    }

    private static final class Execution
            implements CommandExecution
    {
        private final CompletableFuture<ToolOutcome<ToolMessages.CommandResult>> completion = new CompletableFuture<>();

        @Override
        public CommandOutput output() { return new CommandOutput("", "", false, false); }

        @Override
        public CompletionStage<ToolOutcome<ToolMessages.CommandResult>> completion() { return completion; }

        @Override
        public void cancel() {}

        @Override
        public CompletionStage<ToolStopResult> stopAndVerify() { return CompletableFuture.completedStage(ToolStopResult.verifiedStopped()); }
    }
}

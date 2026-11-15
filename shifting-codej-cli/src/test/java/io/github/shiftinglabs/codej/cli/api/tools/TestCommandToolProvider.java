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

import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCommandToolProvider
        extends AbstractTestCommandTreeExecutor
{
    private record Assembly(Optional<ToolExecutionBudget> toolExecutionBudget)
            implements ToolSetContext
    {
        @Override
        public AgentSessionContext session() { throw new AssertionError("Assembly not needed by scripted backend"); }

        @Override
        public ConnectorContext connector() { throw new AssertionError("Assembly not needed by scripted backend"); }

        @Override
        public Executor executor() { return Runnable::run; }
    }

    private static final class Backend
            implements CommandToolProvider.Backend
    {
        final ResourceTestContext context = new ResourceTestContext(AbstractTestCommandExecutor.grants());
        boolean supported = true;
        boolean verified = true;
        boolean refuseClose;
        boolean closed;
        int starts;
        int acknowledgements;
        int closes;
        Execution active;

        @Override
        public boolean supportsCommandTrees() { return supported; }

        @Override
        public ToolExecution<ToolMessages.CommandResult> start(ToolMessages.ShellCommand request, ToolExecutionContext context)
        {
            throw new AssertionError("No single-process fallback");
        }

        @Override
        public CommandExecution startTree(ToolMessages.ShellCommand request, CommandLimits limits, ToolExecutionContext invocation)
        {
            checkState(!closed && (active == null || active.acknowledged), "previous command still owned");
            CodingToolSupport.check(CodingToolSupport.command(), invocation);
            invocation.recordPrepared(new ToolResource("command.owned", "command-" + (starts + 1)));
            invocation.checkActive();
            context.trace.add("start");
            starts++;
            active = new Execution();
            return active;
        }

        final class Execution
                implements CommandExecution
        {
            final CompletableFuture<ToolOutcome<ToolMessages.CommandResult>> completion = new CompletableFuture<>();
            boolean stopped;
            boolean acknowledged;

            void finish()
            {
                completion.complete(ToolOutcome.completed(new ToolMessages.CommandResult(0, ProcessExit.Reason.EXITED, "output", "", false, false)));
            }

            @Override
            public CommandOutput output() { return new CommandOutput(completion.isDone() ? "output" : "", "", false, false); }

            @Override
            public CompletionStage<ToolOutcome<ToolMessages.CommandResult>> completion() { return completion.minimalCompletionStage(); }

            @Override
            public void cancel()
            {
                completion.complete(ToolOutcome.cancelled(new ToolFailure("CANCELLED", "Command cancelled")));
            }

            @Override
            public CompletionStage<ToolStopResult> stopAndVerify()
            {
                cancel();
                stopped = verified;
                return CompletableFuture.completedStage(stopped ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Still owned"));
            }

            @Override
            public void committed()
            {
                checkState(completion.isDone() && stopped, "command outcome or shutdown unresolved");
                if (!acknowledged) {
                    acknowledgements++;
                    acknowledged = true;
                }
            }
        }

        @Override
        public void close()
        {
            closes++;
            checkState(!refuseClose && verified, "backend still owns work");
            if (active != null) {
                active.cancel();
            }
            closed = true;
        }
    }

    @Override
    protected Fixture open()
    {
        var backend = new Backend();
        ToolSet set = new CommandToolProvider().open(new CommandToolProvider.Configuration(CommandLimits.defaults(), (limits, context) -> backend),
                new Assembly(Optional.of(ToolExecutionBudget.bounded(1))));
        assertThat(set.tools()).hasSize(1);
        return new Fixture()
        {
            @Override
            public CommandExecutor executor() { return backend; }

            @Override
            public ResourceTestContext context() { return backend.context; }

            @Override
            public void finish() { backend.active.finish(); }

            @Override
            public void verified(boolean value) { backend.verified = value; }

            @Override
            public int starts() { return backend.starts; }

            @Override
            public int acknowledgements() { return backend.acknowledgements; }

            @Override
            public void close()
            {
                backend.verified = true;
                set.close();
            }
        };
    }

    @Test
    void definitionsAndConfigurationArePureAndFactoryReceivesBorrowedAssemblyAndHostLimits()
    {
        var provider = new CommandToolProvider();
        var opened = new AtomicInteger();
        var limits = CommandLimits.defaults();
        var assembly = new Assembly(Optional.of(ToolExecutionBudget.bounded(1)));
        var backend = new Backend();
        var configuration = new CommandToolProvider.Configuration(limits, (capturedLimits, context) -> {
            assertThat(capturedLimits).isSameAs(limits);
            assertThat(context).isSameAs(assembly);
            opened.incrementAndGet();
            return backend;
        });
        assertThat(provider.id()).isEqualTo("command-execution");
        assertThat(provider.definitions(configuration)).containsExactly(RunCommandTool.DEFINITION);
        assertThat(configuration.limits()).isSameAs(limits);
        assertThat(configuration.factory()).isNotNull();
        assertThat(configuration.toString()).contains("factory=<private>").doesNotContain(configuration.factory().toString());
        assertThat(opened).hasValue(0);
        try (ToolSet set = provider.open(configuration, assembly)) {
            assertThat(opened).hasValue(1);
            assertThat(set.tools()).containsExactlyElementsOf(set.tools());
            assertThat(backend.starts).isZero();
        }
        assertThat(backend.closed).isTrue();
        assertThat(backend.closes).isEqualTo(1);
    }

    @Test
    void unbudgetedOpenFailsBeforeCallingTheFactory()
    {
        var opened = new AtomicInteger();
        var config = new CommandToolProvider.Configuration(CommandLimits.defaults(), (limits, context) -> {
            opened.incrementAndGet();
            return new Backend();
        });
        assertThatThrownBy(() -> new CommandToolProvider().open(config, new Assembly(Optional.empty()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(opened).hasValue(0);
        ToolSetContext oldAssembly = new ToolSetContext()
        {
            @Override
            public AgentSessionContext session() { throw new AssertionError(); }

            @Override
            public ConnectorContext connector() { throw new AssertionError(); }

            @Override
            public Executor executor() { return Runnable::run; }
        };
        assertThat(oldAssembly.toolExecutionBudget()).isEmpty();
        assertThatThrownBy(() -> new CommandToolProvider().open(config, oldAssembly)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedInspectionKeepsTheBackendOwnedAndFailedCloseIsRetryable()
    {
        var backend = new Backend();
        backend.supported = false;
        var provider = new CommandToolProvider();
        var config = new CommandToolProvider.Configuration(CommandLimits.defaults(), (limits, context) -> backend);
        ToolSet set = provider.open(config, new Assembly(Optional.of(ToolExecutionBudget.bounded(1))));
        assertThatThrownBy(set::tools).isInstanceOf(IllegalArgumentException.class);
        backend.refuseClose = true;
        assertThatThrownBy(set::close).isInstanceOf(IllegalStateException.class);
        assertThat(backend.closed).isFalse();
        backend.refuseClose = false;
        set.close();
        set.close();
        assertThat(backend.closes).isEqualTo(2);
        assertThat(backend.closed).isTrue();
        assertThatThrownBy(set::tools).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingConfigurationAndNullFactoryResults()
    {
        var provider = new CommandToolProvider();
        var assembly = new Assembly(Optional.of(ToolExecutionBudget.bounded(1)));
        var config = new CommandToolProvider.Configuration(CommandLimits.defaults(), (limits, context) -> null);
        assertThatThrownBy(() -> new CommandToolProvider.Configuration(null, config.factory())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommandToolProvider.Configuration(config.limits(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.definitions(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.open(null, assembly)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.open(config, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.open(config, assembly)).isInstanceOf(NullPointerException.class);
    }
}

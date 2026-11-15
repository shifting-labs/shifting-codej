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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shiftinglabs.codej.Access;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.ClaudeApi;
import io.github.shiftinglabs.codej.Decision;
import io.github.shiftinglabs.codej.DeepSeekApi;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.TurnResult;
import io.github.shiftinglabs.codej.cli.api.tools.CommandExecution;
import io.github.shiftinglabs.codej.cli.api.tools.CommandLimits;
import io.github.shiftinglabs.codej.cli.api.tools.CommandOutput;
import io.github.shiftinglabs.codej.cli.api.tools.CommandToolProvider;
import io.github.shiftinglabs.codej.cli.api.tools.ToolMessages;
import io.github.shiftinglabs.codej.cli.process.ProcessExit;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.sdk.testing.AnthropicHttpFixture;
import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestApiCommandTools
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    Path directory;

    private record ToolUse(String type, String id, String name, ToolMessages.ShellCommand input) {}
    private record Function(String name, String arguments) {}
    private record Call(int index, String id, String type, Function function) {}
    private record Delta(List<Call> tool_calls) {}

    private static final class Budget
            implements ToolExecutionBudget
    {
        final ToolExecutionBudget bounded = ToolExecutionBudget.bounded(1);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger acquired = new AtomicInteger();

        @Override
        public Lease acquire(Context context)
                throws InterruptedException
        {
            var lease = bounded.acquire(context);
            active.incrementAndGet();
            acquired.incrementAndGet();
            return new Lease()
            {
                boolean released;

                @Override
                public synchronized void close()
                {
                    if (!released) {
                        lease.close();
                        active.decrementAndGet();
                        released = true;
                    }
                }
            };
        }
    }

    /** Scripted host implementation, without an OS command or a claim of native containment. */
    private static final class Backend
            implements CommandToolProvider.Backend
    {
        final Budget budget;
        int starts;
        int acknowledgements;
        int closed;
        boolean verified = true;
        boolean supported = true;
        boolean stopped;

        Backend(Budget budget) { this.budget = budget; }

        @Override
        public boolean supportsCommandTrees() { return supported; }

        @Override
        public ToolExecution<ToolMessages.CommandResult> start(ToolMessages.ShellCommand request, ToolExecutionContext context)
        {
            throw new AssertionError("Legacy shell was invoked");
        }

        @Override
        public CommandExecution startTree(ToolMessages.ShellCommand request, CommandLimits limits, ToolExecutionContext context)
        {
            context.checkActive();
            assertThat(budget.active).hasValue(1);
            assertThat(request.argv()).containsExactly("/usr/bin/true");
            assertThat(limits).isEqualTo(CommandLimits.defaults());
            context.recordPrepared(new ToolResource("command.owned", "example-command"));
            starts++;
            context.progress("Command output ready");
            var result = new ToolMessages.CommandResult(0, ProcessExit.Reason.EXITED, "result sk-command-test", "", false, false);
            CompletionStage<ToolOutcome<ToolMessages.CommandResult>> completion = CompletableFuture.completedStage(ToolOutcome.completed(result));
            return new CommandExecution()
            {
                boolean acknowledged;

                @Override
                public CommandOutput output() { return new CommandOutput(result.stdout(), "", false, false); }

                @Override
                public CompletionStage<ToolOutcome<ToolMessages.CommandResult>> completion() { return completion; }

                @Override
                public void cancel() {}

                @Override
                public CompletionStage<ToolStopResult> stopAndVerify()
                {
                    stopped = verified;
                    return CompletableFuture.completedStage(stopped ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Still owned"));
                }

                @Override
                public void committed()
                {
                    checkState(stopped, "Command still owned");
                    if (!acknowledged) {
                        acknowledgements++;
                        acknowledged = true;
                    }
                }
            };
        }

        @Override
        public void close()
        {
            checkState(starts == 0 || stopped, "Command still owned");
            closed++;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publicFacadesUseTheSameJavaProviderWithApprovalBudgetJournalRedactionAndResume(boolean claude)
            throws Exception
    {
        try (var f = new Fixture(claude)) {
            var approvals = new AtomicInteger();
            var options = SessionOptions.in(directory).access(Access.READ_ONLY).approvals(request -> {
                approvals.incrementAndGet();
                return CompletableFuture.completedStage(Decision.ALLOW_ONCE);
            }).build();
            io.github.shiftinglabs.codej.SessionRef reference;
            try (Agent agent = f.agent(true); Session session = agent.startSession(options)) {
                f.replyWithCommand();
                f.replyText();
                assertThat(session.run(Input.text("Run command"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(approvals).hasValue(1);
                assertThat(f.backend.starts).isEqualTo(1);
                assertThat(f.backend.acknowledgements).isEqualTo(1);
                assertThat(f.budget.acquired).hasValue(1);
                assertThat(f.budget.active).hasValue(0);
                assertThat(f.lastBody()).contains("result", "example-command").doesNotContain("sk-command-test");
                reference = session.ref().orElseThrow();
            }
            assertThat(f.backend.closed).isEqualTo(1);
            try (var paths = Files.walk(directory.resolve("profile"))) {
                assertThat(paths.filter(Files::isRegularFile).map(path -> {
                    try {
                        return Files.readString(path);
                    }
                    catch (java.io.IOException failure) {
                        throw new AssertionError(failure);
                    }
                }).anyMatch(text -> text.contains("command.owned") && text.contains("example-command"))).isTrue();
            }
            try (Agent agent = f.agent(true); Session session = agent.resumeSession(reference, options)) {
                f.replyText();
                assertThat(session.run(Input.text("Continue"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.backend.starts).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void denialPreventsPreparedResourcesAndBudgetAcquisition(boolean claude)
            throws Exception
    {
        try (var f = new Fixture(claude)) {
            var options = SessionOptions.in(directory).access(Access.READ_ONLY)
                    .approvals(request -> CompletableFuture.completedStage(Decision.DENY)).build();
            try (Agent agent = f.agent(true); Session session = agent.startSession(options)) {
                f.replyWithCommand();
                f.replyText();
                assertThat(session.run(Input.text("Denied command"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(f.backend.starts).isZero();
                assertThat(f.budget.acquired).hasValue(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingBudgetAndUnsupportedBackendFailDuringSessionOpen(boolean claude)
            throws Exception
    {
        try (var f = new Fixture(claude)) {
            var options = SessionOptions.in(directory).access(Access.READ_ONLY).build();
            try (Agent agent = f.agent(false)) {
                assertThatThrownBy(() -> agent.startSession(options)).isInstanceOf(RuntimeException.class);
                assertThat(f.opened).hasValue(0);
            }
            f.backend.supported = false;
            try (Agent agent = f.agent(true)) {
                assertThatThrownBy(() -> agent.startSession(options)).isInstanceOf(RuntimeException.class);
                assertThat(f.opened).hasValue(1);
                assertThat(f.backend.closed).isEqualTo(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void uncertainShutdownRetainsTheSharedLeaseUntilVerificationAndDoesNotAcknowledgeTheResult(boolean claude)
            throws Exception
    {
        try (var f = new Fixture(claude)) {
            f.backend.verified = false;
            var options = SessionOptions.in(directory).access(Access.READ_ONLY)
                    .approvals(request -> CompletableFuture.completedStage(Decision.ALLOW_ONCE)).build();
            Agent agent = f.agent(true);
            Session session = agent.startSession(options);
            try {
                f.replyWithCommand();
                assertThat(session.run(Input.text("Unverified command"))).isInstanceOf(TurnResult.Failed.class);
                assertThat(f.budget.active).hasValue(1);
                assertThat(f.backend.acknowledgements).isZero();
                assertThatThrownBy(session::close).isInstanceOf(RuntimeException.class);
                assertThat(f.backend.closed).isZero();
            }
            finally {
                f.backend.verified = true;
                session.close();
                agent.close();
            }
            assertThat(f.budget.active).hasValue(0);
            assertThat(f.backend.starts).isEqualTo(1);
            assertThat(f.backend.acknowledgements).isZero();
        }
    }

    private final class Fixture
            implements AutoCloseable
    {
        final AnthropicHttpFixture anthropic;
        final DeepSeekHttpFixture deepseek;
        final Budget budget = new Budget();
        Backend backend = new Backend(budget);
        final AtomicInteger opened = new AtomicInteger();

        Fixture(boolean claude)
                throws Exception
        {
            anthropic = claude ? new AnthropicHttpFixture() : null;
            deepseek = claude ? null : new DeepSeekHttpFixture();
        }

        Agent agent(boolean budgeted)
        {
            var configuration = new CommandToolProvider.Configuration(CommandLimits.defaults(), (limits, context) -> {
                assertThat(context.toolExecutionBudget()).containsSame(budget);
                if (opened.getAndIncrement() > 0) {
                    backend = new Backend(budget);
                }
                return backend;
            });
            if (anthropic != null) {
                var builder = ClaudeApi.builder().model(AnthropicHttpFixture.MODEL).apiKey(() -> "sk-command-test").baseUrl(anthropic.baseUrl())
                        .toolProvider(new CommandToolProvider(), configuration).profileDirectory(directory.resolve("profile")).stateDirectory(directory.resolve("state"));
                if (budgeted) {
                    builder.toolExecutionBudget(budget);
                }
                return builder.build();
            }
            var builder = DeepSeekApi.builder().model(DeepSeekHttpFixture.MODEL).apiKey(() -> "sk-command-test").baseUrl(deepseek.baseUrl())
                    .toolProvider(new CommandToolProvider(), configuration).profileDirectory(directory.resolve("profile")).stateDirectory(directory.resolve("state"));
            if (budgeted) {
                builder.toolExecutionBudget(budget);
            }
            return builder.build();
        }

        void replyWithCommand()
                throws Exception
        {
            var request = new ToolMessages.ShellCommand(List.of("/usr/bin/true"), ".", 1);
            if (anthropic != null) {
                var block = new ToolUse("tool_use", "example-command", "run_command", request);
                String start = AnthropicHttpFixture.event("content_block_start", "\"index\":0,\"content_block\":" + JSON.writeValueAsString(block));
                anthropic.reply(200, AnthropicHttpFixture.start() + start
                        + AnthropicHttpFixture.event("content_block_stop", "\"index\":0") + AnthropicHttpFixture.end("tool_use"));
            }
            else {
                var delta = new Delta(List.of(new Call(0, "example-command", "function", new Function("run_command", JSON.writeValueAsString(request)))));
                deepseek.reply(200, DeepSeekHttpFixture.chunk(DeepSeekHttpFixture.JSON.valueToTree(delta), "tool_calls", true) + "data: [DONE]\n\n");
            }
        }

        void replyText()
        {
            if (anthropic != null) {
                anthropic.reply(200, AnthropicHttpFixture.textStream("Done"));
            }
            else {
                deepseek.reply(200, DeepSeekHttpFixture.textStream("Done"));
            }
        }

        String lastBody() { return anthropic != null ? anthropic.requests().getLast().body().toString() : deepseek.requests().getLast().body().toString(); }

        @Override
        public void close()
        {
            if (anthropic != null) {
                anthropic.close();
            }
            if (deepseek != null) {
                deepseek.close();
            }
        }
    }
}

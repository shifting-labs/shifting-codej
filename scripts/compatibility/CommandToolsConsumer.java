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

import io.github.shiftinglabs.codej.Access;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.ClaudeApi;
import io.github.shiftinglabs.codej.Decision;
import io.github.shiftinglabs.codej.DeepSeekApi;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.cli.api.tools.CommandExecution;
import io.github.shiftinglabs.codej.cli.api.tools.CommandExecutor;
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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK + shaded jar only; scripted host backend, never an OS process or a native-containment claim. */
public final class CommandToolsConsumer
{
    public static void main(String[] args)
            throws Exception
    {
        CommandExecutor legacy = (request, context) -> { throw new AssertionError("Unexpected legacy execution"); };
        require(!legacy.supportsCommandTrees(), "Legacy backend advertised trees");
        for (boolean claude : new boolean[] {true, false}) {
            Path root = Path.of(args[0]).resolve(claude ? "claude" : "deepseek");
            Files.createDirectories(root);
            var backends = new ArrayList<Backend>();
            var shared = ToolExecutionBudget.bounded(1);
            var config = new CommandToolProvider.Configuration(CommandLimits.defaults(), (limits, context) -> {
                require(context.toolExecutionBudget().orElseThrow() == shared, "Shared budget missing");
                var backend = new Backend();
                backends.add(backend);
                return backend;
            });
            AtomicInteger approvals = new AtomicInteger();
            var options = SessionOptions.in(root).access(Access.READ_ONLY).approvals(request -> {
                approvals.incrementAndGet();
                return CompletableFuture.completedStage(Decision.ALLOW_ONCE);
            }).build();
            try (var f = new ContextCompactionConsumer.Fixture(claude, root)) {
                URI endpoint = URI.create("http://127.0.0.1:" + f.server.getAddress().getPort());
                try (Agent agent = claude
                        ? ClaudeApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                                .profileDirectory(root.resolve("profile")).stateDirectory(root.resolve("state"))
                                .toolExecutionBudget(shared).toolProvider(new CommandToolProvider(), config).build()
                        : DeepSeekApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                                .profileDirectory(root.resolve("profile")).stateDirectory(root.resolve("state"))
                                .toolExecutionBudget(shared).toolProvider(new CommandToolProvider(), config).build();
                        Session session = agent.startSession(options)) {
                    f.commandRequested.set(true);
                    ContextCompactionConsumer.completed(session, "Run the scripted command");
                    require(f.requests.get() == 2 && approvals.get() == 1, "Unexpected inference/approval count");
                    require(f.lastBody.get().contains("scripted-output"), "Typed command output missing");
                }
                require(f.failure.get() == null, "Fake HTTP failed");
            }
            require(backends.size() == 1 && backends.getFirst().starts == 1 && backends.getFirst().acknowledgements == 1
                    && backends.getFirst().closed, "Backend lifecycle mismatch");
        }
        System.out.println("PASS: Java host command provider, shared admission, typed output and both API facades; JDK + jar only");
    }

    private static final class Backend
            implements CommandToolProvider.Backend
    {
        int starts;
        int acknowledgements;
        boolean closed;

        @Override
        public boolean supportsCommandTrees() { return true; }

        @Override
        public ToolExecution<ToolMessages.CommandResult> start(ToolMessages.ShellCommand request, ToolExecutionContext context)
        {
            throw new AssertionError("Single-process fallback invoked");
        }

        @Override
        public CommandExecution startTree(ToolMessages.ShellCommand request, CommandLimits limits, ToolExecutionContext context)
        {
            context.checkActive();
            require(request.argv().equals(List.of("/usr/bin/true")) && limits.equals(CommandLimits.defaults()), "Command/limits changed");
            context.recordPrepared(new ToolResource("command.owned", "packaged-command"));
            starts++;
            return new CommandExecution()
            {
                boolean stopped;
                boolean acknowledged;

                @Override
                public CommandOutput output() { return new CommandOutput("scripted-output", "", false, false); }

                @Override
                public CompletionStage<ToolOutcome<ToolMessages.CommandResult>> completion()
                {
                    return CompletableFuture.completedStage(ToolOutcome.completed(new ToolMessages.CommandResult(
                            0, ProcessExit.Reason.EXITED, output().stdout(), "", false, false)));
                }

                @Override
                public void cancel() { stopped = true; }

                @Override
                public CompletionStage<ToolStopResult> stopAndVerify()
                {
                    stopped = true;
                    return CompletableFuture.completedStage(ToolStopResult.verifiedStopped());
                }

                @Override
                public void committed()
                {
                    require(stopped, "Acknowledged before shutdown verification");
                    if (!acknowledged) { acknowledgements++; acknowledged = true; }
                }
            };
        }

        @Override
        public void close() { closed = true; }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) { throw new AssertionError(message); }
    }
}

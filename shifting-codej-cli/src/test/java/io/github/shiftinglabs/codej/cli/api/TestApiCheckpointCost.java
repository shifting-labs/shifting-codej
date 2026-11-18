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
package io.github.shiftinglabs.codej.cli.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.api.tools.FakeCodingSupervisor;
import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.ToolServer;
import io.github.shiftinglabs.codej.cli.spi.ConnectorContext;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCall;
import io.github.shiftinglabs.codej.cli.spi.model.ModelCapabilities;
import io.github.shiftinglabs.codej.cli.spi.model.ModelClient;
import io.github.shiftinglabs.codej.cli.spi.model.ModelEventSink;
import io.github.shiftinglabs.codej.cli.spi.model.ModelHistory;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkpoint write cost per turn with the real store, a host tool and a fake model whose replay grows like a provider transcript.
 * Run the measurement with {@code -Dcodej.test.checkpointCost=true}; it prints a table per scenario.
 */
class TestApiCheckpointCost
{
    private static final int DEFAULT_CAP = 4 * 1024 * 1024;

    @TempDir
    Path directory;

    private int runs;

    @Test
    void savesPerTurnAreFourPerToolCallPlusThree()
            throws Exception
    {
        for (int tools : new int[] {0, 1, 3}) {
            Run run = run(profile(), Optional.empty(), tools, 16, 3, DEFAULT_CAP);
            assertThat(run.turns()).hasSize(3).allSatisfy(turn -> {
                assertThat(turn.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
                assertThat(turn.saves()).isEqualTo(4 * tools + 3);
            });
        }
    }

    @Test
    void journalKeepsToolFactsWithoutASecondCopyOfTheirOutput()
            throws Exception
    {
        ApiCheckpoint last = run(profile(), Optional.empty(), 2, 1000, 2, DEFAULT_CAP).last().orElseThrow();
        assertThat(last.journal()).filteredOn(ApiJournal.ToolResult.class::isInstance).hasSize(4)
                .allSatisfy(entry -> assertThat(((ApiJournal.ToolResult) entry).text()).isEmpty());
        assertThat(last.history().exchanges().stream().mapToLong(TestApiCheckpointCost::toolText).sum()).isEqualTo(4000);
    }

    @Test
    void fullCheckpointFailsTheTurnCleanlyAndAResumedSessionContinues()
            throws Exception
    {
        Path profile = profile();
        Run full = run(profile, Optional.empty(), 1, 2000, 100, 32 * 1024);
        TurnCost failed = full.turns().getLast();
        assertThat(failed.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(full.failure()).hasValueSatisfying(reason -> assertThat(reason).contains("checkpoint is full"));
        assertThat(full.resumable()).isTrue();
        assertThat(full.last().orElseThrow().recoverable()).isTrue();
        assertThat(full.last().orElseThrow().receipts()).noneMatch(ApiToolReceipt::uncertain);

        Run resumed = run(profile, Optional.of(full.reference()), 1, 2000, 2, 1024 * 1024);
        assertThat(resumed.turns()).extracting(TurnCost::state).containsOnly(AgentTurnResult.State.COMPLETED);
    }

    @Test
    void startedToolWhoseOutputCannotFitSettlesWithAMarker()
            throws Exception
    {
        Run run = run(profile(), Optional.empty(), 1, 60_000, 1, 64 * 1024);
        assertThat(run.turns()).extracting(TurnCost::state).containsExactly(AgentTurnResult.State.COMPLETED);
        assertThat(run.model().toolResults).containsExactly(ApiCheckpoint.UNRECORDED_RESULT);
        assertThat(run.last().orElseThrow().recoverable()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "codej.test.checkpointCost", matches = "true")
    void measureCheckpointGrowth()
            throws Exception
    {
        report("3 tool calls x 4 KiB output per turn", run(profile(), Optional.empty(), 3, 4096, 40, DEFAULT_CAP));
        report("3 tool calls x 60 KiB output per turn", run(profile(), Optional.empty(), 3, 60_000, 40, DEFAULT_CAP));
    }

    private Path profile() { return directory.resolve("run-" + runs++); }

    private Run run(Path profile, Optional<AgentSessionRef> resume, int toolsPerTurn, int outputChars, int turns, int cap)
            throws Exception
    {
        String output = source(outputChars);
        Meter meter = new Meter();
        ApiSessionRepository.Factory repositories = (root, id, resuming, config) -> {
            ApiSessionRepository delegate = ApiSessionStore.open(root, id, resuming, config);
            Path file = root.resolve("api-sessions").resolve(id).resolve("checkpoint.json");
            meter.last = delegate.state();
            return new ApiSessionRepository()
            {
                @Override
                public ApiCheckpoint state() { return delegate.state(); }

                @Override
                public void save(ApiCheckpoint next)
                {
                    long started = System.nanoTime();
                    delegate.save(next);
                    meter.nanos += System.nanoTime() - started;
                    meter.saves++;
                    try {
                        meter.bytes += Files.size(file);
                    }
                    catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                    meter.last = next;
                }

                @Override
                public ModelInput.UserMessage stage(String text, List<AgentAttachment> attachments) { return delegate.stage(text, attachments); }

                @Override
                public void close() { delegate.close(); }
            };
        };
        Tool read = Tool.of("read", "Reads a file", ReadFile.class, arguments -> output);
        GrowingModel model = new GrowingModel(toolsPerTurn);
        var config = ApiEngineConfig.builder(new ConnectorIdentity("fake-api", "work"), new AgentLaneId("fake-api-work"), "model", () -> model)
                .maximumSessions(1).maximumCheckpointBytes(cap).build();
        var context = new AgentSessionContext("task", directory, directory.resolve("runtime"), directory.resolve("runtime/provider"), AgentPermissionPolicy.AUTO,
                resume, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                Optional.empty(), Optional.empty(), SessionAccess.TOOLS_ONLY, "Host instructions", List.of(read));
        List<TurnCost> costs = new ArrayList<>();
        Optional<String> failure = Optional.empty();
        try (var engine = new ApiEngine(config, new ConnectorContext(profile, new FakeCodingSupervisor(), ToolServer.DEFAULT), repositories, ApiPromptAssembler.DEFAULT);
                AgentSession session = engine.open(context, _ -> {})) {
            for (int turn = 1; turn <= turns; turn++) {
                meter.reset();
                long started = System.nanoTime();
                AgentTurnResult result;
                try {
                    result = session.start(new AgentTurnCommand("turn-" + turn, "Step " + turn, List.of(), Duration.ZERO))
                            .completion().toCompletableFuture().get(2, TimeUnit.MINUTES);
                }
                catch (ApiSessionRepository.CheckpointFull full) {
                    // The user message itself no longer fits; the turn never starts.
                    costs.add(new TurnCost(turn, meter.saves, meter.bytes, meter.nanos, System.nanoTime() - started, AgentTurnResult.State.FAILED));
                    failure = Optional.of(full.getMessage());
                    break;
                }
                costs.add(new TurnCost(turn, meter.saves, meter.bytes, meter.nanos, System.nanoTime() - started, result.state()));
                if (result.state() != AgentTurnResult.State.COMPLETED) {
                    failure = result.terminalReason();
                    break;
                }
            }
            return new Run(costs, Optional.ofNullable(meter.last), failure, session.resumable(), session.reference(), model);
        }
    }

    private static void report(String scenario, Run run)
    {
        StringBuilder out = new StringBuilder("\n== ").append(scenario).append('\n')
                .append(String.format("%4s %6s %12s %9s %9s %10s%n", "turn", "saves", "written KiB", "save ms", "turn ms", "state"));
        long written = 0;
        long saveNanos = 0;
        long saves = 0;
        for (TurnCost turn : run.turns()) {
            written += turn.bytes();
            saveNanos += turn.saveNanos();
            saves += turn.saves();
            out.append(String.format("%4d %6d %12d %9.1f %9.1f %10s%n", turn.turn(), turn.saves(), turn.bytes() / 1024, turn.saveNanos() / 1e6,
                    turn.turnNanos() / 1e6, turn.state()));
        }
        out.append(String.format("total: %d saves, %.1f MiB written, %.0f ms in save%n", saves, written / 1048576.0, saveNanos / 1e6));
        run.failure().ifPresent(reason -> out.append("stopped: ").append(reason).append('\n'));
        out.append("session resumable: ").append(run.resumable()).append('\n');
        run.last().ifPresent(state -> {
            try {
                long total = ApiCheckpointCodec.encode(state).length;
                long replay = state.replay().map(value -> (long) value.payload().length()).orElse(0L);
                long journal = state.journal().stream().filter(ApiJournal.ToolResult.class::isInstance)
                        .mapToLong(entry -> ((ApiJournal.ToolResult) entry).text().length()).sum();
                long history = state.history().exchanges().stream().mapToLong(TestApiCheckpointCost::toolText).sum();
                out.append(String.format("last committed checkpoint: %d KiB, phase %s, recoverable %b; tool text in journal %d KiB, in history %d KiB; replay payload %d KiB%n",
                        total / 1024, state.phase(), state.recoverable(), journal / 1024, history / 1024, replay / 1024));
            }
            catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        System.out.print(out);
    }

    private static long toolText(ModelHistory.Exchange exchange)
    {
        return exchange.input() instanceof ModelInput.ToolResults results ? results.results().stream().mapToLong(result -> result.text().length()).sum() : 0;
    }

    private static String source(int characters)
    {
        String line = "    String value = \"x\";\n";
        return line.repeat(characters / line.length() + 1).substring(0, characters);
    }

    private record ReadFile(String path) {}

    private record TurnCost(int turn, int saves, long bytes, long saveNanos, long turnNanos, AgentTurnResult.State state) {}

    private record Run(List<TurnCost> turns, Optional<ApiCheckpoint> last, Optional<String> failure, boolean resumable, AgentSessionRef reference, GrowingModel model) {}

    private static final class Meter
    {
        int saves;
        long bytes;
        long nanos;
        ApiCheckpoint last;

        void reset()
        {
            saves = 0;
            bytes = 0;
            nanos = 0;
        }
    }

    /** Asks for the tool a fixed number of times per turn; its replay is the whole transcript as JSON, as native adapters keep it. */
    private static final class GrowingModel
            implements ModelClient
    {
        final List<String> toolResults = new CopyOnWriteArrayList<>();
        private final int toolsPerTurn;
        private final List<String> transcript = new ArrayList<>();
        private int rounds;
        private int calls;

        GrowingModel(int toolsPerTurn) { this.toolsPerTurn = toolsPerTurn; }

        @Override
        public ModelCapabilities capabilities(String model) { return new ModelCapabilities(true, Set.of(), Set.of(), Set.of()); }

        @Override
        public AgentProviderStatus probe(Optional<String> model)
        {
            return new AgentProviderStatus(new AgentLaneId("fake-api"), AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none());
        }

        @Override
        public ModelCall generate(ModelRequest request, ModelEventSink events)
        {
            if (request.input() instanceof ModelInput.UserMessage user) {
                transcript.add(user.text());
                rounds = 0;
            }
            else if (request.input() instanceof ModelInput.ToolResults results) {
                results.results().forEach(result -> {
                    transcript.add(result.text());
                    toolResults.add(result.text());
                });
            }
            ModelResponse response;
            if (rounds < toolsPerTurn) {
                rounds++;
                calls++;
                var call = new ModelResponse.ToolCall("call-" + calls, "read", "{\"path\":\"src/File" + calls + ".java\"}");
                transcript.add(call.arguments());
                response = new ModelResponse("", List.of(call), ModelResponse.StopReason.TOOL_CALLS, replay(), Optional.empty());
            }
            else {
                transcript.add("Done.");
                response = new ModelResponse("Done.", List.of(), ModelResponse.StopReason.COMPLETED, replay(), Optional.empty());
            }
            CompletableFuture<ModelResponse> done = CompletableFuture.completedFuture(response);
            return new ModelCall()
            {
                @Override
                public CompletionStage<ModelResponse> completion() { return done.minimalCompletionStage(); }

                @Override
                public void cancel() {}
            };
        }

        private ModelReplayState replay()
        {
            try {
                return new ModelReplayState("fake-api", 1, ApiCheckpointCodec.JSON.writeValueAsString(transcript));
            }
            catch (JsonProcessingException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void close() {}
    }
}

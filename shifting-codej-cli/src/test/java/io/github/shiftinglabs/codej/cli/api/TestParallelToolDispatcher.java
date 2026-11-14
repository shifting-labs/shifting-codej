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

import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineObservation.Admission;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(5)
class TestParallelToolDispatcher
{
    private static final ToolRequirements READ = ToolRequirements.builder().read(Path.of("data")).build();
    private static final ToolRequirements GRANTS = ToolRequirements.builder().read(Path.of(".")).write(Path.of("."))
            .require(ToolCapability.HOST_INVOKE).require(ToolCapability.NETWORK_ACCESS).require(new ToolCapability("custom.effect")).build();

    record Input(int index) {}

    @Test
    void explicitAsyncReadsOverlapAndSettleOutOfOrderOnTheCoordinator()
    {
        Fixture f = new Fixture();
        Step first = f.step();
        Step second = f.step();
        first.start = execution -> {};
        second.start = execution -> {
            assertThat(first.result).isNull();
            assertThat(first.future).isNotDone();
            second.future.complete(ToolOutcome.completed("second"));
        };
        second.commit = () -> first.future.complete(ToolOutcome.completed("first"));
        f.run(2);
        assertThat(f.trace).containsSubsequence("start:0", "start:1", "result:1", "commit:1", "result:0", "commit:0");
        assertThat(first.result).isEqualTo(new ToolRegistration.Result("first", false));
        assertThat(second.result).isEqualTo(new ToolRegistration.Result("second", false));
        assertThat(f.trace).containsSubsequence("policy:0", "approval:0", "intent:0", "policy:1", "approval:1", "intent:1");
        assertThat(f.maximumActive).isEqualTo(2);
    }

    @Test
    void bothTheWindowAndSharedCapacityBoundAdmissionWithoutSelfDeadlock()
    {
        for (boolean legacyBudget : List.of(false, true)) {
            Fixture f = new Fixture();
            var bounded = ToolExecutionBudget.bounded(1);
            f.budget = legacyBudget ? bounded::acquire : bounded;
            Step first = f.step();
            Step second = f.step();
            second.start = execution -> {
                assertThat(first.commits).isEqualTo(1);
                second.future.complete(ToolOutcome.completed("second"));
            };
            f.run(3);
            assertThat(f.maximumActive).isEqualTo(1);
        }
        Fixture f = new Fixture();
        Step first = f.step();
        Step second = f.step();
        Step third = f.step();
        first.start = execution -> {};
        third.start = execution -> {
            assertThat(second.commits).isEqualTo(1);
            assertThat(first.commits).isZero();
            third.future.complete(ToolOutcome.completed("third"));
        };
        third.commit = () -> first.future.complete(ToolOutcome.completed("first"));
        f.run(2);
        assertThat(f.maximumActive).isEqualTo(2);
    }

    @Test
    void unsafeImplementationsLegacyWrappersWritesAndOpaqueEffectsAreExclusiveBarriers()
    {
        for (String barrier : List.of("unsafe", "legacy", "write", "host", "network", "custom")) {
            Fixture f = new Fixture();
            Step first = f.step();
            Step middle = f.step();
            middle.name = "barrier";
            middle.requirements = switch (barrier) {
                case "write" -> ToolRequirements.builder().write(Path.of("data")).build();
                case "host" -> ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();
                case "network" -> ToolRequirements.builder().require(ToolCapability.NETWORK_ACCESS).build();
                case "custom" -> ToolRequirements.builder().require(new ToolCapability("custom.effect")).build();
                default -> READ;
            };
            f.barrierSafe = !barrier.equals("unsafe");
            f.legacyBarrier = barrier.equals("legacy");
            Step last = f.step();
            middle.start = execution -> {
                assertThat(first.commits).isEqualTo(1);
                middle.future.complete(ToolOutcome.completed("middle"));
            };
            last.start = execution -> {
                assertThat(middle.commits).isEqualTo(1);
                last.future.complete(ToolOutcome.completed("last"));
            };
            f.run(3);
            assertThat(f.maximumActive).isEqualTo(1);
        }
    }

    @Test
    void denialsAndInvalidArgumentsDoNotConsumeLeasesOrStartHandlers()
    {
        for (String mode : List.of("invalid", "permission", "policy", "approval")) {
            Fixture f = new Fixture();
            Step first = f.step();
            first.start = execution -> {};
            Step second = f.step();
            second.reject = mode;
            second.afterResult = () -> first.future.complete(ToolOutcome.completed("first"));
            f.run(2);
            assertThat(second.result.error()).isTrue();
            assertThat(second.execution).isNull();
            assertThat(f.trace).doesNotContain("intent:1", "start:1");
            assertThat(first.commits).isEqualTo(1);
        }
    }

    @Test
    void exhaustedTurnBudgetCancelsAndDrainsTheAdmittedPrefixWithoutErasingSuccess()
    {
        Fixture f = new Fixture();
        Step first = f.step();
        first.start = execution -> {};
        first.onCancel = () -> first.future.complete(ToolOutcome.completed("observed success"));
        Step second = f.step();
        f.maximumCalls = 1;
        assertThatThrownBy(() -> f.run(2)).isInstanceOf(ToolDispatcher.Limit.class);
        assertThat(first.cancels).isEqualTo(1);
        assertThat(first.result.text()).isEqualTo("observed success");
        assertThat(second.execution).isNull();
        f.assertCapacity(4);
    }

    @Test
    void stopPersistenceAndAcknowledgementFailuresDrainOtherExecutionsAndRetainUnsafeClaims()
            throws Exception
    {
        for (String mode : List.of("stop", "result", "commit", "outcome")) {
            Fixture f = new Fixture();
            f.budget = ToolExecutionBudget.bounded(2);
            Step first = f.step();
            first.start = execution -> {};
            first.onCancel = () -> first.future.complete(ToolOutcome.completed("first survived"));
            Step second = f.step();
            second.verified = !mode.equals("stop");
            second.failResult = mode.equals("result");
            second.failCommit = mode.equals("commit");
            if (mode.equals("outcome")) {
                second.start = execution -> second.future.completeExceptionally(new IllegalStateException("private failure"));
            }
            assertThatThrownBy(() -> f.run(2)).isInstanceOf(RuntimeException.class);
            assertThat(first.cancels).isEqualTo(1);
            assertThat(first.result.text()).isEqualTo("first survived");
            assertThat(second.cancels).isEqualTo(1);
            assertThat(second.recorded).isEqualTo(mode.equals("commit") ? 1 : 0);
            try (var free = f.budget.tryAcquire(f.budgetContext()).orElseThrow()) {
                assertThat(f.budget.tryAcquire(f.budgetContext())).isEmpty();
            }
            second.verified = true;
            second.failCommit = false;
            f.dispatcher.verifyStopped();
            f.dispatcher.resourcesClosed();
            f.assertCapacity(2);
            assertThat(second.recorded).isEqualTo(mode.equals("commit") ? 1 : 0);
        }
    }

    @Test
    void cancellationReachesAllHandlesWhileTheCoordinatorIsAwaitingApproval()
            throws Exception
    {
        Fixture f = new Fixture();
        Step first = f.step();
        first.start = execution -> {};
        first.onCancel = () -> first.future.complete(ToolOutcome.completed("first survived"));
        Step second = f.step();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        second.approval = () -> {
            waiting.countDown();
            try {
                release.await();
            }
            catch (InterruptedException failure) {
                throw new CancellationException();
            }
        };
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread worker = Thread.ofPlatform().daemon().name("parallel-fixture").start(() -> {
            try {
                f.run(2);
                result.complete(null);
            }
            catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            f.cancelled.set(true);
            f.turn.cancel();
            assertThat(first.cancels).isEqualTo(1);
            worker.interrupt();
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
            assertThat(first.result.text()).isEqualTo("first survived");
            assertThat(second.execution).isNull();
        }
        finally {
            release.countDown();
            worker.interrupt();
            worker.join(1000);
            assertThat(worker.isAlive()).isFalse();
        }
        f.assertCapacity(4);
    }

    @Test
    void interruptedVerificationKeepsTheObservedSuccessAndUsesOneStopProof()
    {
        Fixture f = new Fixture();
        Step first = f.step();
        first.interruptProof = true;
        f.step();
        f.run(2);
        assertThat(first.result.text()).isEqualTo("value");
        assertThat(first.proofs).isEqualTo(1);
        assertThat(first.proofReads).isEqualTo(2);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        f.assertCapacity(4);
    }

    @Test
    void uncertainStartupDrainsOtherHandlesAndKeepsItsReservationUntilResourcesClose()
    {
        Fixture f = new Fixture();
        f.budget = ToolExecutionBudget.bounded(2);
        Step first = f.step();
        first.start = execution -> {};
        first.onCancel = () -> first.future.complete(ToolOutcome.completed("first survived"));
        Step broken = f.step();
        broken.start = execution -> { throw new IllegalStateException("startup failed without a handle"); };
        assertThatThrownBy(() -> f.run(2)).isInstanceOf(ToolDispatcher.Uncertain.class);
        assertThat(first.result.text()).isEqualTo("first survived");
        assertThat(broken.result).isNull();
        f.dispatcher.verifyStopped();
        try {
            var free = f.budget.tryAcquire(f.budgetContext()).orElseThrow();
            try {
                assertThat(f.budget.tryAcquire(f.budgetContext())).isEmpty();
            }
            finally {
                free.close();
            }
        }
        catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
        broken.future.complete(ToolOutcome.completed("reconciled by owner"));
        f.dispatcher.resourcesClosed();
        f.assertCapacity(2);
    }

    private static final class Step
    {
        final int index;
        final CompletableFuture<ToolOutcome<String>> future = new CompletableFuture<>();
        String name = "parallel";
        String reject = "";
        ToolRequirements requirements = READ;
        Consumer<ToolExecutionContext> start = execution -> future.complete(ToolOutcome.completed("value"));
        Runnable commit = () -> {};
        Runnable onCancel = () -> future.complete(ToolOutcome.cancelled(new ToolFailure("CANCELLED", "Stopped")));
        Runnable afterResult = () -> {};
        Runnable approval = () -> {};
        ToolExecutionContext execution;
        ToolRegistration.Result result;
        boolean verified = true;
        boolean failResult;
        boolean failCommit;
        boolean interruptProof;
        int proofs;
        int proofReads;
        int cancels;
        int commits;
        int recorded;

        Step(int index) { this.index = index; }
    }

    private static final class Fixture
    {
        final List<Step> steps = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        ToolExecutionBudget budget = ToolExecutionBudget.bounded(4);
        ToolDispatcher dispatcher;
        ToolDispatcher.Turn turn;
        boolean barrierSafe;
        boolean legacyBarrier;
        int maximumCalls = 64;
        int active;
        int maximumActive;

        Step step()
        {
            Step step = new Step(steps.size());
            steps.add(step);
            return step;
        }

        void run(int window)
        {
            dispatcher = new ToolDispatcher(ApiToolRegistry.registered(List.of(registration("parallel", true, false),
                    registration("barrier", barrierSafe, legacyBarrier))), Duration.ofMillis(50), Optional.of(budget));
            turn = dispatcher.turn(maximumCalls);
            var invocations = steps.stream().map(step -> new ToolDispatcher.Invocation(new ToolDispatcher.Call("call" + step.index,
                    step.name, step.reject.equals("invalid") ? "{}" : "{\"index\":" + step.index + "}", null), context(step))).toList();
            turn.dispatchBatch(invocations, window);
        }

        ToolRegistration registration(String name, boolean safe, boolean legacy)
        {
            AgentTool<Input, String> tool = new AgentTool<>()
            {
                @Override
                public ToolDefinition<Input, String> definition()
                {
                    return ToolDefinitions.of(ToolDescriptor.builder(name, "Fixture").build(), RecordToolParameters.of(Input.class), ToolOutput::text);
                }

                @Override
                public boolean parallelSafe() { return safe; }

                @Override
                public ToolRequirements requirements(Input input, ToolPreparationContext context) { return steps.get(input.index()).requirements; }

                @Override
                public ToolExecution<String> execute(Input input, ToolExecutionContext execution)
                {
                    Step step = steps.get(input.index());
                    step.execution = execution;
                    active++;
                    maximumActive = Math.max(maximumActive, active);
                    trace.add("start:" + step.index);
                    step.start.accept(execution);
                    return new ToolExecution<>()
                    {
                        @Override
                        public CompletionStage<ToolOutcome<String>> completion() { return step.future.minimalCompletionStage(); }

                        @Override
                        public void cancel()
                        {
                            step.cancels++;
                            assertThat(execution.cancellationRequested()).isTrue();
                            step.onCancel.run();
                        }

                        @Override
                        public CompletionStage<ToolStopResult> stopAndVerify()
                        {
                            step.proofs++;
                            if (step.interruptProof) {
                                CompletableFuture<ToolStopResult> proof = new CompletableFuture<>()
                                {
                                    @Override
                                    public ToolStopResult get(long timeout, TimeUnit unit)
                                            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException
                                    {
                                        if (++step.proofReads == 1) {
                                            throw new InterruptedException("Cancellation raced the proof wait");
                                        }
                                        return super.get(timeout, unit);
                                    }
                                };
                                proof.complete(ToolStopResult.verifiedStopped());
                                return proof;
                            }
                            return CompletableFuture.completedStage(step.verified ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("not stopped"));
                        }

                        @Override
                        public void committed()
                        {
                            if (step.failCommit) {
                                throw new IllegalStateException("commit failed");
                            }
                            step.commits++;
                            active--;
                            trace.add("commit:" + step.index);
                            step.commit.run();
                        }
                    };
                }
            };
            return ToolRegistration.create(tool, legacy, true, Optional.empty(), failure -> failure.code() + ":" + failure.message());
        }

        ToolDispatcher.Context context(Step step)
        {
            return new ToolDispatcher.Context()
            {
                @Override
                public String invocationId() { return "call" + step.index; }

                @Override
                public Optional<Instant> deadline() { return Optional.empty(); }

                @Override
                public boolean cancelled() { return cancelled.get(); }

                @Override
                public void checkStop()
                {
                    if (cancelled()) {
                        throw new CancellationException();
                    }
                }

                @Override
                public ToolRequirements grants() { return step.reject.equals("permission") ? ToolRequirements.none() : GRANTS; }

                @Override
                public Optional<Admission> policyRejection(ToolDispatcher.Call call,
                        ToolRequirements requirements)
                {
                    trace.add("policy:" + step.index);
                    return step.reject.equals("policy") ? Optional.of(Admission.POLICY_DENIED) : Optional.empty();
                }

                @Override
                public boolean allowed(ToolDispatcher.Call call)
                {
                    trace.add("approval:" + step.index);
                    step.approval.run();
                    return !step.reject.equals("approval");
                }

                @Override
                public String rejection(String code, String message) { return code; }

                @Override
                public void started(ToolDispatcher.Call call) {}

                @Override
                public void recordStarted(ToolDispatcher.Call call) { trace.add("intent:" + step.index); }

                @Override
                public void recordResult(ToolRegistration.Result result)
                {
                    if (step.failResult) {
                        throw new IllegalStateException("persistence failed");
                    }
                    assertThat(step.result).isNull();
                    step.result = result;
                    step.recorded++;
                    trace.add("result:" + step.index);
                    step.afterResult.run();
                }

                @Override
                public void completed(ToolDispatcher.Call call, ToolRegistration.Result result) {}

                @Override
                public void recordPrepared(ToolResource resource) {}

                @Override
                public void progress(String message) {}

                @Override
                public void interruptible(boolean value) {}
            };
        }

        ToolExecutionBudget.Context budgetContext()
        {
            return new ToolExecutionBudget.Context()
            {
                @Override
                public String invocationId() { return "capacity-check"; }

                @Override
                public Optional<Instant> deadline() { return Optional.of(Instant.now().plusSeconds(1)); }

                @Override
                public boolean cancellationRequested() { return false; }
            };
        }

        void assertCapacity(int count)
        {
            List<ToolExecutionBudget.Lease> acquired = new ArrayList<>();
            try {
                for (int index = 0; index < count; index++) {
                    acquired.add(budget.tryAcquire(budgetContext()).orElseThrow());
                }
                assertThat(budget.tryAcquire(budgetContext())).isEmpty();
            }
            catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
            finally {
                acquired.forEach(ToolExecutionBudget.Lease::close);
            }
        }
    }
}

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

import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLegacyToolAdapter
{
    record Input(int count)
    {
        Input
        {
            if (count < 0) {
                throw new IllegalArgumentException("count is negative");
            }
        }
    }

    @Test
    void preservesMetadataAndDefersOriginalRecordBindingUntilExecution()
            throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        Tool original = Tool.of("count", "Count", Input.class, input -> {
            calls.incrementAndGet();
            return "ok";
        });
        var adapter = LegacyToolAdapter.builder(original).build();
        var definition = adapter.definition();
        assertThat(definition.descriptor().name()).isEqualTo(original.name());
        assertThat(definition.descriptor().description()).isEqualTo(original.description());
        assertThat(definition.parameters().schema().json()).isEqualTo(original.inputSchema());
        var input = definition.parameters().decode(() -> "{\"count\":-1}");
        assertThat(adapter.requirements(input, new Context()).capabilities()).containsExactly(ToolCapability.HOST_INVOKE);
        assertThat(calls).hasValue(0);
        assertThat(done(adapter.execute(input, new Context())).state()).isEqualTo(ToolOutcome.State.FAILED);
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> definition.parameters().decode(() -> "{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alternateEnvelopesAndOutputsKeepLegacyHostLimits()
            throws Exception
    {
        String large = "x".repeat(70000);
        String schema = "{\"description\":\"" + large + "\"}";
        var adapter = LegacyToolAdapter.builder(Tool.withSchema("large", large, schema, input -> large)).build();
        var arguments = adapter.definition().parameters().decode(() -> "{\"value\":\"" + large + "\"}");
        assertThat(arguments.toString()).doesNotContain(large);
        var execution = adapter.execute(arguments, new Context());
        var output = adapter.definition().results().encode(done(execution).value().orElseThrow());
        assertThat(output.text()).isEqualTo(large);
        assertThat(output.mediaType()).isEqualTo("text/plain");
        assertThat(output.toString()).doesNotContain(large);
        execution.completion().toCompletableFuture().obtrudeException(new IllegalStateException("observer only"));
        execution.cancel();
        assertThat(done(execution).value()).contains(large);
        assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
    }

    @Test
    void queuedCancellationPreventsEffectsAndRetainsBorrowedExecutor()
            throws Exception
    {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        var adapter = LegacyToolAdapter.builder(Tool.withSchema("example", "Example", "{}", input -> {
            calls.incrementAndGet();
            return "ok";
        }))
                .executor(task -> { assertThat(queued.getAndSet(task)).isNull(); }).build();
        var execution = adapter.execute(new LegacyToolAdapter.Arguments("{}"), new Context());
        assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isFalse();
        execution.cancel();
        execution.cancel();
        queued.getAndSet(null).run();
        assertThat(calls).hasValue(0);
        assertThat(done(execution).state()).isEqualTo(ToolOutcome.State.CANCELLED);
        assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
        var second = adapter.execute(new LegacyToolAdapter.Arguments("{}"), new Context());
        queued.get().run();
        assertThat(done(second).value()).contains("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void runningCallbackMustReturnEvenWhenExecutorThrowsAfterAcceptance()
            throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        var adapter = LegacyToolAdapter.builder(Tool.withSchema("example", "Example", "{}", input -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return "observed success";
        })).executor(task -> {
            worker.set(Thread.ofPlatform().daemon().start(task));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            }
            catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
            throw new RejectedExecutionException("accepted before rejection");
        }).build();
        var execution = adapter.execute(new LegacyToolAdapter.Arguments("{}"), new Context());
        try {
            execution.cancel();
            assertThat(execution.completion().toCompletableFuture()).isNotDone();
            assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isFalse();
        }
        finally {
            release.countDown();
            worker.get().join(2000);
        }
        assertThat(done(execution).value()).contains("observed success");
        assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
    }

    @Test
    void rejectionAndFailuresAreSanitizedWithOriginalAsyncExceptionBoundary()
            throws Exception
    {
        Tool failing = Tool.withSchema("example", "Example", "{}", input -> { throw new IllegalStateException("private diagnostic"); });
        AtomicReference<Throwable> observed = new AtomicReference<>();
        var inline = LegacyToolAdapter.builder(failing).failures(failure -> {
            observed.set(failure);
            return ToolOutcome.failed(new io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure("SAFE", "Safe failure"));
        }).build();
        assertThat(done(inline.execute(new LegacyToolAdapter.Arguments("{}"), new Context())).failure().orElseThrow().message()).isEqualTo("Safe failure");
        assertThat(observed.get()).isInstanceOf(IllegalStateException.class);
        var deferred = LegacyToolAdapter.builder(failing).executor(Runnable::run).failures(failure -> {
            observed.set(failure);
            return ToolOutcome.failed(new io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure("SAFE", "Safe failure"));
        }).build();
        done(deferred.execute(new LegacyToolAdapter.Arguments("{}"), new Context()));
        assertThat(observed.get()).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
        var rejected = LegacyToolAdapter.builder(failing).executor(task -> { throw new RejectedExecutionException("private diagnostic"); }).build();
        var result = done(rejected.execute(new LegacyToolAdapter.Arguments("{}"), new Context()));
        assertThat(result.failure().orElseThrow().message()).isEqualTo("Host tool failed");
        assertThat(result.toString()).doesNotContain("private diagnostic");
        var nullResult = LegacyToolAdapter.builder(Tool.withSchema("empty", "Empty", "{}", input -> null)).build();
        assertThat(done(nullResult.execute(new LegacyToolAdapter.Arguments("{}"), new Context())).state()).isEqualTo(ToolOutcome.State.FAILED);
    }

    @Test
    void permissionsCancellationAndDeadlinePreventCallbackEffects()
            throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        var adapter = LegacyToolAdapter.builder(Tool.withSchema("example", "Example", "{}", input -> {
            calls.incrementAndGet();
            return "ok";
        })).build();
        var context = new Context();
        context.grants = ToolRequirements.none();
        assertThatThrownBy(() -> adapter.execute(new LegacyToolAdapter.Arguments("{}"), context)).isInstanceOf(SecurityException.class);
        context.grants = ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();
        context.cancelled = true;
        assertThat(done(adapter.execute(new LegacyToolAdapter.Arguments("{}"), context)).state()).isEqualTo(ToolOutcome.State.CANCELLED);
        context.cancelled = false;
        context.deadline = Optional.of(Instant.EPOCH);
        assertThat(done(adapter.execute(new LegacyToolAdapter.Arguments("{}"), context)).state()).isEqualTo(ToolOutcome.State.CANCELLED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void stopVerificationAndCommitAreIndependentAndRetryable()
            throws Exception
    {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger verifies = new AtomicInteger();
        var adapter = LegacyToolAdapter.builder(Tool.withSchema("example", "Example", "{}", input -> "ok"))
                .stopVerifier(() -> verifies.incrementAndGet() == 1 ? ToolStopResult.unverified("Still owned") : ToolStopResult.verifiedStopped())
                .committed(() -> {
                    if (commits.incrementAndGet() == 1) {
                        throw new IllegalStateException("retry acknowledgement");
                    }
                }).build();
        var execution = adapter.execute(new LegacyToolAdapter.Arguments("{}"), new Context());
        assertThat(done(execution).value()).contains("ok");
        assertThat(commits).hasValue(0);
        assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isFalse();
        assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
        assertThatThrownBy(execution::committed).isInstanceOf(IllegalStateException.class);
        execution.committed();
        execution.committed();
        assertThat(commits).hasValue(2);
    }

    @Test
    void inlineErrorsRetainTheIncompleteReceiptBehavior()
    {
        var adapter = LegacyToolAdapter.builder(Tool.withSchema("example", "Example", "{}", input -> { throw new AssertionError("fatal callback"); })).build();
        assertThatThrownBy(() -> adapter.execute(new LegacyToolAdapter.Arguments("{}"), new Context())).isInstanceOf(AssertionError.class);
    }

    private static ToolOutcome<String> done(ToolExecution<String> execution)
            throws Exception
    {
        return execution.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static final class Context
            implements ToolExecutionContext
    {
        private ToolRequirements grants = ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();
        private Optional<Instant> deadline = Optional.empty();
        private boolean cancelled;

        @Override
        public String invocationId() { return "invocation"; }

        @Override
        public Optional<Instant> deadline() { return deadline; }

        @Override
        public ToolRequirements grants() { return grants; }

        @Override
        public boolean cancellationRequested() { return cancelled; }

        @Override
        public void recordPrepared(ToolResource resource) { throw new AssertionError("Opaque callback has no engine-owned resources"); }
    }
}

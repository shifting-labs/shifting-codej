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
package io.github.shiftinglabs.codej.cli.spi.tool;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolExecutionBudget
{
    @Test
    void capacityIsBoundedAndLeaseReleaseIsIdempotent()
            throws Exception
    {
        var budget = ToolExecutionBudget.bounded(2);
        var first = budget.acquire(context(Optional.empty(), () -> false));
        var second = budget.acquire(context(Optional.empty(), () -> false));
        try {
            assertFull(budget);
            first.close();
            first.close();
            try (var replacement = budget.acquire(context(Optional.of(Instant.MAX), () -> false))) {
                assertFull(budget);
            }
        }
        finally {
            first.close();
            second.close();
        }
        try (var firstAgain = budget.acquire(context(Optional.empty(), () -> false));
                var secondAgain = budget.acquire(context(Optional.empty(), () -> false))) {
            assertFull(budget);
        }
    }

    @Test
    void waitingAcquisitionProceedsOnlyAfterRelease()
            throws Exception
    {
        var budget = ToolExecutionBudget.bounded(1);
        var held = budget.acquire(context(Optional.empty(), () -> false));
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<ToolExecutionBudget.Lease> waiting = new CompletableFuture<>();
        Thread worker = waiter(budget, context(Optional.empty(), () -> {
            entered.countDown();
            return false;
        }), waiting);
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(waiting).isNotDone();
            held.close();
            try (var acquired = waiting.get(1, TimeUnit.SECONDS)) {
                assertFull(budget);
            }
        }
        finally {
            held.close();
            worker.interrupt();
            worker.join(1000);
            assertThat(worker.isAlive()).isFalse();
        }
    }

    @Test
    void cancellationIsRecheckedWithoutAnInterruptAndDoesNotConsumeCapacity()
            throws Exception
    {
        var budget = ToolExecutionBudget.bounded(1);
        var held = budget.acquire(context(Optional.empty(), () -> false));
        var cancelled = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<ToolExecutionBudget.Lease> waiting = new CompletableFuture<>();
        Thread worker = waiter(budget, context(Optional.empty(), () -> {
            entered.countDown();
            return cancelled.get();
        }), waiting);
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
            assertThat(worker.isInterrupted()).isFalse();
        }
        finally {
            held.close();
            worker.interrupt();
            worker.join(1000);
        }
        try (var acquired = budget.acquire(context(Optional.empty(), () -> false))) {
            assertFull(budget);
        }
    }

    @Test
    void interruptionAbortsWaitingAndNeverReleasesAnotherOwnersLease()
            throws Exception
    {
        var budget = ToolExecutionBudget.bounded(1);
        var held = budget.acquire(context(Optional.empty(), () -> false));
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<ToolExecutionBudget.Lease> waiting = new CompletableFuture<>();
        Thread worker = waiter(budget, context(Optional.empty(), () -> {
            entered.countDown();
            return false;
        }), waiting);
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            assertFull(budget);
        }
        finally {
            held.close();
            worker.interrupt();
            worker.join(1000);
        }
    }

    @Test
    void cancelledExpiredAndMalformedContextsFailWithoutLeakingAnAcquiredSlot()
            throws Exception
    {
        var budget = ToolExecutionBudget.bounded(1);
        assertThatThrownBy(() -> budget.acquire(context(Optional.empty(), () -> true))).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> budget.acquire(context(Optional.of(Instant.MIN), () -> false))).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> budget.acquire(context(null, () -> false))).isInstanceOf(NullPointerException.class);
        AtomicInteger checks = new AtomicInteger();
        assertThatThrownBy(() -> budget.acquire(context(Optional.empty(), () -> checks.incrementAndGet() == 2))).isInstanceOf(CancellationException.class);
        checks.set(0);
        assertThatThrownBy(() -> budget.acquire(context(Optional.empty(), () -> {
            if (checks.incrementAndGet() == 2) {
                throw new IllegalStateException("post-acquisition failure");
            }
            return false;
        }))).isInstanceOf(IllegalStateException.class);
        try (var acquired = budget.acquire(context(Optional.empty(), () -> false))) {
            assertFull(budget);
        }
    }

    @Test
    void invalidLimitsAndNullContextsAreRejectedBeforeAdmission()
    {
        assertThatThrownBy(() -> ToolExecutionBudget.bounded(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolExecutionBudget.bounded(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolExecutionBudget.bounded(1).acquire(null)).isInstanceOf(NullPointerException.class);
    }

    private static void assertFull(ToolExecutionBudget budget)
    {
        assertThatThrownBy(() -> {
            try (var unexpected = budget.acquire(context(Optional.of(Instant.now().plusMillis(25)), () -> false))) {
                throw new AssertionError("Budget admitted more work than its configured limit");
            }
        }).isInstanceOf(CancellationException.class);
    }

    private static Thread waiter(ToolExecutionBudget budget, ToolExecutionBudget.Context context, CompletableFuture<ToolExecutionBudget.Lease> completion)
    {
        return Thread.ofPlatform().daemon().name("budget-fixture").start(() -> {
            try {
                completion.complete(budget.acquire(context));
            }
            catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
    }

    private static ToolExecutionBudget.Context context(Optional<Instant> deadline, BooleanSupplier cancelled)
    {
        return new ToolExecutionBudget.Context()
        {
            @Override
            public String invocationId() { return "invocation"; }

            @Override
            public Optional<Instant> deadline() { return deadline; }

            @Override
            public boolean cancellationRequested() { return cancelled.getAsBoolean(); }
        };
    }
}

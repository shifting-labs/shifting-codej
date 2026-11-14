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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Fair blocking admission on the caller's existing worker, with bounded cancellation rechecks. */
final class BoundedToolExecutionBudget
        implements ToolExecutionBudget
{
    private static final Duration RECHECK = Duration.ofMillis(50);
    private final Semaphore slots;

    BoundedToolExecutionBudget(int maximumConcurrent)
    {
        checkArgument(maximumConcurrent > 0, "maximumConcurrent must be positive");
        slots = new Semaphore(maximumConcurrent, true);
    }

    @Override
    public Lease acquire(Context context)
            throws InterruptedException
    {
        requireNonNull(context, "context is null");
        while (true) {
            if (slots.tryAcquire(waitNanos(context), TimeUnit.NANOSECONDS)) {
                return acquired(context);
            }
        }
    }

    @Override
    public Optional<Lease> tryAcquire(Context context)
            throws InterruptedException
    {
        requireNonNull(context, "context is null");
        waitNanos(context);
        return slots.tryAcquire(0, TimeUnit.NANOSECONDS) ? Optional.of(acquired(context)) : Optional.empty();
    }

    private Lease acquired(Context context)
    {
        try {
            waitNanos(context);
            var released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) {
                    slots.release();
                }
            };
        }
        catch (RuntimeException | Error failure) {
            slots.release();
            throw failure;
        }
    }

    private static long waitNanos(Context context)
    {
        if (context.cancellationRequested()) {
            throw new CancellationException("Tool budget admission cancelled");
        }
        Optional<Instant> deadline = requireNonNull(context.deadline(), "deadline is null");
        if (deadline.isPresent()) {
            Duration remaining = Duration.between(Instant.now(), deadline.orElseThrow());
            if (remaining.isNegative() || remaining.isZero()) {
                throw new CancellationException("Tool budget admission deadline exceeded");
            }
            return remaining.compareTo(RECHECK) < 0 ? remaining.toNanos() : RECHECK.toNanos();
        }
        return RECHECK.toNanos();
    }
}

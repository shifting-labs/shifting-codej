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

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Deletes old conversation transcripts from the homes agents keep them in, for {@code keepSessionsFor}: one sweep per home, first
 * two minutes after an agent on it is built, then every six hours, until the last agent on that home closes.
 */
final class SessionSweeps
{
    private static final System.Logger log = System.getLogger(SessionSweeps.class.getName());
    private static final Duration FIRST = Duration.ofMinutes(2);
    private static final Duration EVERY = Duration.ofHours(6);
    // ponytail: one scheduler thread for every home, so sweeps run one at a time; enough for a handful of homes.
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("session-sweeps").factory());
    private static final Map<Path, Sweep> SWEEPS = new HashMap<>();

    private SessionSweeps() {}

    /** Deletes the transcripts in one home last used before a time, and says how many it deleted. */
    @FunctionalInterface
    interface Pruner
    {
        int prune(Path home, Instant deleteBefore)
                throws IOException;
    }

    /**
     * Keeps {@code home}'s transcripts for {@code keep} while the result is open. Agents on one home share its sweep, which keeps the
     * shortest time any of them asked for.
     */
    static AutoCloseable keep(Path home, Duration keep, Pruner pruner)
    {
        requireNonNull(home, "home is null");
        checkArgument(keep.isPositive(), "keep is not positive: %s", keep);
        requireNonNull(pruner, "pruner is null");
        Sweep sweep;
        synchronized (SWEEPS) {
            sweep = SWEEPS.computeIfAbsent(home, _ -> new Sweep(home, pruner));
            sweep.keeps.add(keep);
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                release(sweep, keep);
            }
        };
    }

    /** The times the open agents on {@code home} keep its transcripts for; empty when nothing sweeps it. */
    static List<Duration> keeps(Path home)
    {
        synchronized (SWEEPS) {
            Sweep sweep = SWEEPS.get(home);
            return sweep == null ? ImmutableList.of() : ImmutableList.copyOf(sweep.keeps);
        }
    }

    /** Sweeps {@code home} now, as its schedule would. */
    static void sweep(Path home)
    {
        Sweep sweep;
        synchronized (SWEEPS) {
            sweep = SWEEPS.get(home);
        }
        if (sweep != null) {
            sweep.run();
        }
    }

    private static void release(Sweep sweep, Duration keep)
    {
        synchronized (SWEEPS) {
            sweep.keeps.remove(keep);
            if (sweep.keeps.isEmpty() && SWEEPS.remove(sweep.home, sweep)) {
                sweep.future.cancel(false);
            }
        }
    }

    private static final class Sweep
            implements Runnable
    {
        private final Path home;
        private final Pruner pruner;
        private final List<Duration> keeps = new ArrayList<>();
        private final ScheduledFuture<?> future;

        private Sweep(Path home, Pruner pruner)
        {
            this.home = home;
            this.pruner = pruner;
            this.future = SCHEDULER.scheduleWithFixedDelay(this, FIRST.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void run()
        {
            Duration keep;
            synchronized (SWEEPS) {
                if (keeps.isEmpty()) {
                    return;
                }
                keep = Collections.min(keeps);
            }
            try {
                int deleted = pruner.prune(home, Instant.now().minus(keep));
                if (deleted > 0) {
                    log.log(System.Logger.Level.INFO, "Deleted {0} transcript folders older than {1} in {2}", deleted, keep, home);
                }
            }
            catch (IOException | RuntimeException failure) {
                // The next sweep tries again; one unreadable home never stops another's.
                log.log(System.Logger.Level.WARNING, "Could not delete old transcripts in " + home, failure);
            }
        }
    }
}

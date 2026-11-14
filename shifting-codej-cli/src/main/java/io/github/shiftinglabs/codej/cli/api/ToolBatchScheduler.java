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

import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** One coordinator overlaps explicit asynchronous read/pure operations; all effects remain exclusive barriers. */
final class ToolBatchScheduler
{
    private final ToolDispatcher.Turn turn;
    private final int maximumParallel;
    private final List<ToolDispatcher.Pending> running = new ArrayList<>();

    ToolBatchScheduler(ToolDispatcher.Turn turn, int maximumParallel)
    {
        this.turn = turn;
        this.maximumParallel = maximumParallel;
    }

    void run(Iterable<ToolDispatcher.Invocation> invocations)
    {
        try {
            for (var invocation : invocations) {
                invocation.context().checkStop();
                var candidate = turn.prepare(invocation);
                if (candidate.isEmpty()) {
                    continue;
                }
                var prepared = candidate.orElseThrow();
                boolean parallel = prepared.registration().parallelSafe && prepared.requirements().writes().isEmpty()
                        && prepared.requirements().capabilities().stream().allMatch(ToolCapability.WORKSPACE_READ::equals);
                if (!parallel) {
                    drain();
                }
                while (running.size() >= maximumParallel) {
                    settleOne();
                }
                if (!turn.admit(prepared)) {
                    continue;
                }
                while (true) {
                    var started = turn.start(prepared, !running.isEmpty());
                    if (started.isPresent()) {
                        running.add(started.orElseThrow());
                        break;
                    }
                    settleOne();
                }
                if (!parallel) {
                    drain();
                }
            }
            drain();
        }
        catch (RuntimeException | Error failure) {
            turn.cancel();
            RuntimeException drainFailure = null;
            for (var pending : running) {
                try {
                    turn.finish(pending);
                }
                catch (RuntimeException | Error stopped) {
                    try {
                        turn.verifyFailed(pending);
                    }
                    catch (RuntimeException unverified) {
                        drainFailure = unverified;
                    }
                    if (drainFailure == null) {
                        drainFailure = new ToolDispatcher.Uncertain("Parallel tool outcome could not be durably settled");
                    }
                }
            }
            running.clear();
            if (drainFailure != null) {
                throw drainFailure;
            }
            throw failure;
        }
    }

    private void drain()
    {
        while (!running.isEmpty()) {
            settleOne();
        }
    }

    private void settleOne()
    {
        ToolDispatcher.Context context = running.getFirst().prepared().invocation().context();
        try {
            context.checkStop();
            context.interruptible(true);
            try {
                CompletableFuture.anyOf(running.stream().map(ToolDispatcher.Pending::completion).toArray(CompletableFuture[]::new)).get();
            }
            catch (InterruptedException failure) {
                turn.cancel();
                context.checkStop();
                throw new ToolDispatcher.Limit("Parallel tool wait interrupted");
            }
            catch (ExecutionException | CancellationException failure) {
                // A settled exceptional outcome is interpreted by the same dispatcher path as serial execution.
            }
        }
        finally {
            context.interruptible(false);
            Thread.interrupted();
        }
        var pending = running.stream().filter(value -> value.completion().isDone()).findFirst().orElseThrow();
        running.remove(pending);
        try {
            turn.finish(pending);
        }
        catch (RuntimeException | Error failure) {
            turn.cancel();
            turn.verifyFailed(pending);
            throw failure;
        }
    }
}

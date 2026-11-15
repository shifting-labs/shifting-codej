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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.shiftinglabs.codej.cli.spi.extension.EngineObservation.Admission;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionBudget;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static java.util.Objects.requireNonNull;

/** Shared dispatch lifecycle; persistence and frontend callbacks are supplied as narrow session-bound ports. */
final class ToolDispatcher
{
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Call(String id, String name, String arguments, String eventId)
    {
        Call
        {
            requireNonNull(id, "id is null");
            requireNonNull(name, "name is null");
            requireNonNull(arguments, "arguments is null");
            eventId = eventId == null ? id : eventId;
        }

        @Override
        public String toString() { return "ToolCall[" + name + ", redacted]"; }
    }

    interface Context
            extends ToolPreparationContext
    {
        void checkStop();

        boolean cancelled();

        ToolRequirements grants();

        boolean allowed(Call call);

        default Optional<Admission> policyRejection(Call call, ToolRequirements requirements) { return Optional.empty(); }

        default void decided(Call call, Admission admission) {}

        String rejection(String code, String legacyMessage);

        void started(Call call);

        void recordStarted(Call call);

        void recordResult(ToolRegistration.Result result);

        void completed(Call call, ToolRegistration.Result result);

        void recordPrepared(ToolResource resource);

        void progress(String message);

        void interruptible(boolean value);
    }

    private record Ownership(boolean durable, Optional<ToolExecutionBudget.Lease> lease) {}

    private final ApiToolRegistry registry;
    private final Duration stopTimeout;
    private final Optional<ToolExecutionBudget> budget;
    private final Map<ToolRegistration.Active, Ownership> active = new ConcurrentHashMap<>();
    private final List<ToolExecutionBudget.Lease> startupClaims = new ArrayList<>();

    ToolDispatcher(ApiToolRegistry registry, Duration stopTimeout)
    {
        this(registry, stopTimeout, Optional.empty());
    }

    ToolDispatcher(ApiToolRegistry registry, Duration stopTimeout, Optional<ToolExecutionBudget> budget)
    {
        this.registry = registry;
        this.stopTimeout = stopTimeout;
        this.budget = requireNonNull(budget, "budget is null");
    }

    Turn turn(int maximumCalls) { return new Turn(maximumCalls); }

    void committed()
    {
        for (var entry : active.entrySet()) {
            if (entry.getValue().durable()) {
                verify(entry.getKey());
                entry.getKey().committed();
                release(entry.getKey());
            }
        }
    }

    void verifyStopped()
    {
        committed();
        for (var execution : active.keySet()) {
            verify(execution);
            release(execution);
        }
    }

    /** Startup without a returned handle is fenced by the session's resource owners, not cancellation. */
    void resourcesClosed()
    {
        while (!startupClaims.isEmpty()) {
            startupClaims.getLast().close();
            startupClaims.removeLast();
        }
    }

    private void release(ToolRegistration.Active execution)
    {
        Ownership ownership = active.get(execution);
        ownership.lease().ifPresent(ToolExecutionBudget.Lease::close);
        active.remove(execution, ownership);
    }

    private Optional<ToolExecutionBudget.Lease> reserve(Call call, Context context, boolean nonblocking)
    {
        if (budget.isEmpty()) {
            return Optional.empty();
        }
        try {
            context.interruptible(true);
            context.checkStop();
            ToolExecutionBudget.Context admission = new ToolExecutionBudget.Context()
            {
                @Override
                public String invocationId() { return call.eventId(); }

                @Override
                public Optional<Instant> deadline() { return context.deadline(); }

                @Override
                public boolean cancellationRequested() { return context.cancelled(); }
            };
            return nonblocking ? requireNonNull(budget.orElseThrow().tryAcquire(admission), "tool budget returned null")
                    : Optional.of(requireNonNull(budget.orElseThrow().acquire(admission), "tool budget returned no lease"));
        }
        catch (InterruptedException | CancellationException failure) {
            context.checkStop();
            throw new Limit("Tool budget admission interrupted, cancelled or deadline exceeded");
        }
        finally {
            context.interruptible(false);
            Thread.interrupted();
        }
    }

    private void verify(ToolRegistration.Active execution) { verify(execution, false); }

    private void verify(ToolRegistration.Active execution, boolean draining)
    {
        try {
            long began = System.nanoTime();
            CompletableFuture<ToolStopResult> proof = execution.stopAndVerify().toCompletableFuture();
            while (true) {
                long remaining = Math.max(0, stopTimeout.toNanos() - (System.nanoTime() - began));
                try {
                    ToolStopResult stopped = proof.get(remaining, TimeUnit.NANOSECONDS);
                    if (!stopped.stopped()) {
                        throw new Uncertain("Tool termination could not be verified");
                    }
                    return;
                }
                catch (InterruptedException failure) {
                    if (!draining || remaining == 0) {
                        Thread.currentThread().interrupt();
                        throw new Uncertain("Tool termination could not be verified");
                    }
                    // Keep the observed outcome while waiting on the same bounded quiescence proof.
                }
            }
        }
        catch (Uncertain failure) {
            throw failure;
        }
        catch (Exception failure) {
            throw new Uncertain("Tool termination could not be verified");
        }
    }

    record Invocation(Call call, Context context)
    {
        Invocation
        {
            requireNonNull(call, "call is null");
            requireNonNull(context, "context is null");
        }
    }

    record PreparedInvocation(Invocation invocation, ToolRegistration registration, ToolRegistration.Prepared prepared)
    {
        ToolRequirements requirements() { return prepared.requirements(); }
    }

    record Pending(PreparedInvocation prepared, ToolRegistration.Active execution, CompletableFuture<?> completion) {}

    final class Turn
    {
        private final int maximumCalls;
        private final AtomicBoolean aborting = new AtomicBoolean();
        private final Set<ToolRegistration.Active> cancelled = ConcurrentHashMap.newKeySet();
        private int calls;

        Turn(int maximumCalls) { this.maximumCalls = maximumCalls; }

        void cancel()
        {
            aborting.set(true);
            for (var execution : active.keySet()) {
                if (cancelled.add(execution)) {
                    try {
                        execution.cancel();
                    }
                    catch (RuntimeException failure) {
                        // Cancellation is best effort; completion and stop verification still fence ownership.
                    }
                }
            }
        }

        void dispatch(Call call, Context context)
        {
            Optional<PreparedInvocation> prepared = prepare(new Invocation(call, context));
            if (prepared.isPresent() && admit(prepared.orElseThrow())) {
                finish(start(prepared.orElseThrow(), false).orElseThrow());
            }
        }

        void dispatchBatch(Iterable<Invocation> invocations, int maximumParallel)
        {
            if (maximumParallel < 2 || budget.isEmpty()) {
                throw new IllegalArgumentException("Parallel batches require a shared budget and a window greater than one");
            }
            new ToolBatchScheduler(this, maximumParallel).run(invocations);
        }

        Optional<PreparedInvocation> prepare(Invocation invocation)
        {
            Call call = invocation.call();
            Context context = invocation.context();
            context.checkStop();
            if (++calls > maximumCalls) {
                throw new Limit("API turn tool budget exhausted");
            }
            ToolRegistration registration;
            ToolRegistration.Prepared prepared;
            try {
                registration = registry.registration(call.name());
            }
            catch (IllegalArgumentException failure) {
                context.recordResult(new ToolRegistration.Result(context.rejection("INVALID_ARGUMENTS", failure.getMessage()), true));
                context.decided(call, Admission.INVALID_ARGUMENTS);
                return Optional.empty();
            }
            try {
                prepared = registration.prepare(call.arguments(), context);
            }
            catch (IllegalArgumentException failure) {
                context.recordResult(new ToolRegistration.Result(registration.rejection(context, "INVALID_ARGUMENTS", failure.getMessage()), true));
                context.decided(call, Admission.INVALID_ARGUMENTS);
                return Optional.empty();
            }
            if (!context.grants().permits(prepared.requirements())) {
                context.recordResult(new ToolRegistration.Result(registration.rejection(context, "PERMISSION_DENIED", "Tool requirements are not permitted"), true));
                context.decided(call, Admission.PERMISSION_DENIED);
                return Optional.empty();
            }
            return Optional.of(new PreparedInvocation(invocation, registration, prepared));
        }

        boolean admit(PreparedInvocation prepared)
        {
            Call call = prepared.invocation().call();
            Context context = prepared.invocation().context();
            Optional<Admission> rejection = context.policyRejection(call, prepared.requirements());
            if (rejection.isPresent()) {
                Admission admission = rejection.orElseThrow();
                context.recordResult(new ToolRegistration.Result(prepared.registration().rejection(context, admission.name(), "Tool invocation rejected by policy"), true));
                context.decided(call, admission);
                return false;
            }
            context.checkStop();
            if (prepared.registration().approval && !context.allowed(call)) {
                context.recordResult(new ToolRegistration.Result(prepared.registration().rejection(context, "APPROVAL_DENIED", "Tool invocation denied"), true));
                context.decided(call, Admission.APPROVAL_DENIED);
                return false;
            }
            return true;
        }

        Optional<Pending> start(PreparedInvocation prepared, boolean nonblocking)
        {
            Call call = prepared.invocation().call();
            Context context = prepared.invocation().context();
            Optional<ToolExecutionBudget.Lease> lease = reserve(call, context, nonblocking);
            if (nonblocking && lease.isEmpty()) {
                return Optional.empty();
            }
            lease.ifPresent(startupClaims::add);
            boolean invoked = false;
            try {
                context.checkStop();
                context.decided(call, Admission.ADMITTED);
                context.checkStop();
                context.started(call);
                context.checkStop();
                context.recordStarted(call);
                if (context.cancelled()) {
                    context.recordResult(new ToolRegistration.Result("Not executed: turn interrupted before handler invocation", true));
                    context.checkStop();
                }
                ToolRegistration.Active execution;
                try {
                    context.interruptible(true);
                    try {
                        invoked = true;
                        execution = prepared.prepared().start(executionContext(call, context, prepared.requirements(), aborting::get));
                    }
                    catch (Exception failure) {
                        throw new Uncertain("Tool execution ended without an outcome");
                    }
                    active.put(execution, new Ownership(false, lease));
                    lease.ifPresent(startupClaims::remove);
                    if (context.cancelled() || aborting.get()) {
                        cancel();
                    }
                }
                finally {
                    context.interruptible(false);
                    Thread.interrupted();
                }
                return Optional.of(new Pending(prepared, execution, execution.completion().toCompletableFuture()));
            }
            finally {
                if (!invoked && lease.isPresent()) {
                    lease.orElseThrow().close();
                    startupClaims.remove(lease.orElseThrow());
                }
            }
        }

        void finish(Pending pending)
        {
            Context context = pending.prepared().invocation().context();
            ToolRegistration.Active execution = pending.execution();
            ToolRegistration.Result result;
            try {
                context.interruptible(true);
                while (true) {
                    try {
                        result = execution.await();
                        break;
                    }
                    catch (InterruptedException failure) {
                        cancel();
                    }
                    catch (ExecutionException | CancellationException failure) {
                        throw new Uncertain("Tool execution ended without an outcome");
                    }
                }
                Thread.interrupted();
                verify(execution, true);
            }
            finally {
                context.interruptible(false);
                Thread.interrupted();
            }
            context.recordResult(result);
            active.computeIfPresent(execution, (key, ownership) -> new Ownership(true, ownership.lease()));
            execution.committed();
            release(execution);
            context.completed(pending.prepared().invocation().call(), result);
        }

        void verifyFailed(Pending pending) { verify(pending.execution(), true); }
    }

    private ToolExecutionContext executionContext(Call call, Context context, ToolRequirements grants, BooleanSupplier aborting)
    {
        return new ToolExecutionContext()
        {
            @Override
            public String invocationId() { return call.eventId(); }

            @Override
            public Optional<Instant> deadline() { return context.deadline(); }

            @Override
            public ToolRequirements grants() { return grants; }

            @Override
            public boolean cancellationRequested() { return context.cancelled() || aborting.getAsBoolean(); }

            @Override
            public void recordPrepared(ToolResource resource) { context.recordPrepared(resource); }

            @Override
            public void progress(String message)
            {
                ToolExecutionContext.super.progress(message);
                context.progress(message);
            }
        };
    }

    static final class Uncertain
            extends RuntimeException
    {
        Uncertain(String message) { super(message); }
    }

    static final class Limit
            extends RuntimeException
    {
        Limit(String message) { super(message); }
    }
}

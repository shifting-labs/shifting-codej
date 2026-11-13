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
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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

        String rejection(String code, String legacyMessage);

        void started(Call call);

        void recordStarted(Call call);

        void recordResult(ToolRegistration.Result result);

        void completed(Call call, ToolRegistration.Result result);

        void recordPrepared(ToolResource resource);

        void progress(String message);

        void interruptible(boolean value);
    }

    private final ApiToolRegistry registry;
    private final Duration stopTimeout;
    private final Map<ToolRegistration.Active, Boolean> active = new ConcurrentHashMap<>();

    ToolDispatcher(ApiToolRegistry registry, Duration stopTimeout)
    {
        this.registry = registry;
        this.stopTimeout = stopTimeout;
    }

    Turn turn(int maximumCalls) { return new Turn(maximumCalls); }

    void committed()
    {
        for (var entry : active.entrySet()) {
            if (entry.getValue()) {
                verify(entry.getKey());
                entry.getKey().committed();
                active.remove(entry.getKey());
            }
        }
    }

    void verifyStopped()
    {
        committed();
        for (var execution : active.keySet()) {
            verify(execution);
            active.remove(execution);
        }
    }

    private void verify(ToolRegistration.Active execution)
    {
        try {
            ToolStopResult stopped = execution.stopAndVerify().toCompletableFuture().get(stopTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!stopped.stopped()) {
                throw new Uncertain("Tool termination could not be verified");
            }
        }
        catch (Uncertain failure) {
            throw failure;
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new Uncertain("Tool termination could not be verified");
        }
        catch (Exception failure) {
            throw new Uncertain("Tool termination could not be verified");
        }
    }

    final class Turn
    {
        private final int maximumCalls;
        private int calls;

        Turn(int maximumCalls) { this.maximumCalls = maximumCalls; }

        void dispatch(Call call, Context context)
        {
            if (++calls > maximumCalls) {
                throw new Limit("API turn tool budget exhausted");
            }
            ToolRegistration registration;
            ToolRegistration.Prepared prepared;
            try {
                registration = registry.registration(call.name());
                prepared = registration.prepare(call.arguments(), context);
            }
            catch (IllegalArgumentException failure) {
                context.recordResult(new ToolRegistration.Result(context.rejection("INVALID_ARGUMENTS", failure.getMessage()), true));
                return;
            }
            if (!context.grants().permits(prepared.requirements())) {
                context.recordResult(new ToolRegistration.Result(context.rejection("PERMISSION_DENIED", "Tool requirements are not permitted"), true));
                return;
            }
            if (registration.approval && !context.allowed(call)) {
                context.recordResult(new ToolRegistration.Result(context.rejection("APPROVAL_DENIED", "Tool invocation denied"), true));
                return;
            }
            context.checkStop();
            context.started(call);
            context.checkStop();
            context.recordStarted(call);
            if (context.cancelled()) {
                context.recordResult(new ToolRegistration.Result("Not executed: turn interrupted before handler invocation", true));
                context.checkStop();
            }
            ToolRegistration.Active execution;
            ToolRegistration.Result result;
            try {
                context.interruptible(true);
                try {
                    execution = prepared.start(executionContext(call, context, prepared.requirements()));
                }
                catch (Exception failure) {
                    throw new Uncertain("Tool execution ended without an outcome");
                }
                active.put(execution, false);
                if (context.cancelled()) {
                    execution.cancel();
                }
                while (true) {
                    try {
                        result = execution.await();
                        break;
                    }
                    catch (InterruptedException failure) {
                        execution.cancel();
                    }
                    catch (ExecutionException | java.util.concurrent.CancellationException failure) {
                        throw new Uncertain("Tool execution ended without an outcome");
                    }
                }
                Thread.interrupted();
                verify(execution);
            }
            finally {
                context.interruptible(false);
                Thread.interrupted();
            }
            context.recordResult(result);
            active.replace(execution, true);
            execution.committed();
            active.remove(execution);
            context.completed(call, result);
        }
    }

    private ToolExecutionContext executionContext(Call call, Context context, ToolRequirements grants)
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
            public boolean cancellationRequested() { return context.cancelled(); }

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

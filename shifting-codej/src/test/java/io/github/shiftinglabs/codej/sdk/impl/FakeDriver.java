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
import com.google.common.collect.ImmutableSet;
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnInput;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.driver.ProviderTurnRef;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A driver whose sessions and turns a test finishes by hand. */
final class FakeDriver
        implements AgentDriver
{
    final List<FakeSession> sessions = new CopyOnWriteArrayList<>();
    volatile AgentProviderStatus status = new AgentProviderStatus(AgentLaneId.CODEX_CLI, AgentProviderStatus.Readiness.READY, Optional.of("1.0"), "Signed in",
            defaultCapabilities(), Optional.empty());
    final AtomicInteger probes = new AtomicInteger();
    volatile List<ProviderModel> models = ImmutableList.of();
    volatile ProviderQuotaSnapshot quota = ProviderQuotaSnapshot.unavailable("No limits");
    volatile Optional<RuntimeException> quotaFailure = Optional.empty();
    /** Whether a finished turn leaves its session able to take the next one. */
    volatile boolean retains;
    volatile Optional<RuntimeException> openFailure = Optional.empty();
    /** What a session's reference throws, as a driver that started its process but lost its conversation id. */
    volatile Optional<RuntimeException> referenceFailure = Optional.empty();
    /** Whether a session's process cannot be shown to have stopped. */
    volatile boolean stopFails;
    /** Whether a new conversation is saved only once a turn has answered in it, as Claude saves one. */
    volatile boolean savesOnAnswer;

    static AgentCapabilities defaultCapabilities()
    {
        return new AgentCapabilities(true, true, true, ImmutableSet.of(AgentPermissionPolicy.AUTO), ImmutableSet.of("image/png"));
    }

    @Override
    public AgentLaneId id()
    {
        return AgentLaneId.CODEX_CLI;
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return status.capabilities();
    }

    @Override
    public AgentProviderStatus probe()
    {
        probes.incrementAndGet();
        return status;
    }

    @Override
    public List<ProviderModel> models()
    {
        return models;
    }

    @Override
    public ProviderQuotaSnapshot quota()
    {
        quotaFailure.ifPresent(failure -> {
            throw failure;
        });
        return quota;
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        openFailure.ifPresent(failure -> {
            throw failure;
        });
        FakeSession session = new FakeSession(context, events, context.sessionReference().orElseGet(
                () -> new AgentSessionRef(AgentLaneId.CODEX_CLI, 3, UUID.randomUUID().toString())));
        sessions.add(session);
        return session;
    }

    FakeSession last()
    {
        return sessions.getLast();
    }

    final class FakeSession
            implements AgentSession
    {
        final AgentSessionContext context;
        final AgentEventSink events;
        final AgentSessionRef reference;
        final List<FakeTurn> turns = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        volatile boolean parked;
        volatile boolean answered;

        FakeSession(AgentSessionContext context, AgentEventSink events, AgentSessionRef reference)
        {
            this.context = context;
            this.events = events;
            this.reference = reference;
        }

        @Override
        public AgentSessionRef reference()
        {
            referenceFailure.ifPresent(failure -> {
                throw failure;
            });
            return reference;
        }

        @Override
        public boolean retained()
        {
            return parked;
        }

        @Override
        public boolean resumable()
        {
            return !savesOnAnswer || answered || context.sessionReference().isPresent();
        }

        @Override
        public boolean prepareTurn(AgentSessionContext next, AgentEventSink sink)
        {
            return parked && next.model().equals(context.model());
        }

        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            FakeTurn turn = new FakeTurn(command);
            turns.add(turn);
            return turn;
        }

        @Override
        public void close()
        {
            if (stopFails) {
                throw new IllegalStateException("The process is still running");
            }
            closed = true;
        }

        FakeTurn turn()
        {
            return turns.getLast();
        }

        final class FakeTurn
                implements RunningTurn
        {
            final AgentTurnCommand command;
            final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
            final List<AgentTurnInput> steered = new CopyOnWriteArrayList<>();
            volatile Optional<RuntimeException> steerFailure = Optional.empty();
            volatile boolean cancelled;

            FakeTurn(AgentTurnCommand command)
            {
                this.command = command;
            }

            @Override
            public ProviderTurnRef reference()
            {
                return new ProviderTurnRef(command.turnId());
            }

            @Override
            public CompletionStage<AgentTurnResult> completion()
            {
                return completion;
            }

            @Override
            public void steer(AgentTurnInput input)
            {
                steerFailure.ifPresent(failure -> {
                    throw failure;
                });
                steered.add(input);
            }

            @Override
            public void cancel()
            {
                cancelled = true;
                completion.complete(new AgentTurnResult(AgentTurnResult.State.INTERRUPTED, Optional.empty(), Optional.of("Interrupted"), Duration.ofMillis(5), true));
            }

            void end(AgentTurnResult.State state, String reason)
            {
                completion.complete(new AgentTurnResult(state, Optional.empty(), Optional.of(reason), Duration.ofMillis(5), true));
            }

            /** Completes, but with work the provider could not show stopped. */
            void completeUnsettled(String answer)
            {
                parked = retains;
                completion.complete(new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of(answer), Optional.empty(), Duration.ofMillis(5), false));
            }

            void complete(String answer)
            {
                answered = true;
                parked = retains;
                completion.complete(new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of(answer), Optional.empty(), Duration.ofMillis(5), true));
            }
        }
    }
}

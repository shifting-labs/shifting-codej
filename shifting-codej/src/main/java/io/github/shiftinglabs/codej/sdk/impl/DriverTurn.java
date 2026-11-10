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

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.shiftinglabs.codej.AgentEvent;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.DeliveryUncertainException;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Source;
import io.github.shiftinglabs.codej.Turn;
import io.github.shiftinglabs.codej.TurnResult;
import io.github.shiftinglabs.codej.Usage;
import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.AgentDeliveryUncertainException;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnFailureKind;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnInput;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * One turn of a {@link DriverSession}. Its events and then its result go through one sequential queue on the callback executor, so the
 * listener sees them in order, one at a time, and the result completes after the last of them.
 */
final class DriverTurn
        implements Turn
{
    private static final System.Logger log = System.getLogger(DriverTurn.class.getName());

    private final Consumer<AgentEvent> listener;
    private final Executor queue;
    private final boolean steering;
    private final Optional<Duration> timeLimit;
    private final Instant started = Instant.now();
    private final TurnEvents events;
    private final CompletableFuture<TurnResult> result = new CompletableFuture<>();

    // Guarded by this.
    private Optional<RunningTurn> running = Optional.empty();
    /** Events the driver sent before its start returned, delivered after {@link AgentEvent.Started}. */
    private final List<ProviderAgentEvent> early = new ArrayList<>();
    private boolean interrupted;
    private boolean ended;

    DriverTurn(Consumer<AgentEvent> listener, Executor callbacks, boolean steering, Optional<Duration> timeLimit, Function<BigDecimal, Optional<BigDecimal>> conversationCost)
    {
        this.listener = requireNonNull(listener, "listener is null");
        this.events = new TurnEvents(conversationCost);
        this.queue = MoreExecutors.newSequentialExecutor(callbacks);
        this.steering = steering;
        this.timeLimit = requireNonNull(timeLimit, "timeLimit is null");
    }

    @Override
    public CompletionStage<TurnResult> result()
    {
        return result.minimalCompletionStage();
    }

    @Override
    public TurnResult await()
            throws InterruptedException
    {
        try {
            return result.get();
        }
        catch (ExecutionException impossible) {
            throw new IllegalStateException("A turn's result never fails", impossible);
        }
    }

    @Override
    public void steer(Input input)
    {
        requireNonNull(input, "input is null");
        if (!steering) {
            throw new UnsupportedOperationException("This agent cannot add input to a running turn");
        }
        RunningTurn turn;
        synchronized (this) {
            while (running.isEmpty() && !ended) {
                try {
                    wait();
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while the turn started", failure);
                }
            }
            checkState(!ended, "The turn has ended");
            turn = running.orElseThrow();
        }
        try {
            turn.steer(new AgentTurnInput(input.text(), attachments(input)));
        }
        catch (AgentDeliveryUncertainException uncertain) {
            throw new DeliveryUncertainException(DriverSession.message(uncertain), uncertain);
        }
        catch (RuntimeException refused) {
            throw new AgentException(DriverSession.message(refused), refused);
        }
    }

    @Override
    public CompletionStage<TurnResult> interrupt()
    {
        Optional<RunningTurn> turn;
        synchronized (this) {
            interrupted = true;
            turn = running;
        }
        turn.ifPresent(RunningTurn::cancel);
        return result();
    }

    synchronized boolean done()
    {
        return ended;
    }

    synchronized boolean interruptRequested()
    {
        return interrupted;
    }

    /** The provider took the turn; an interrupt asked for before this stops it now. */
    void running(RunningTurn turn)
    {
        boolean cancel;
        synchronized (this) {
            running = Optional.of(turn);
            cancel = interrupted;
            notifyAll();
            if (!ended) {
                send(new AgentEvent.Started(Source.root()));
                early.forEach(event -> events.map(event).ifPresent(this::send));
            }
            early.clear();
        }
        if (cancel) {
            turn.cancel();
        }
    }

    synchronized void deliver(ProviderAgentEvent event)
    {
        if (ended) {
            return;
        }
        if (running.isEmpty()) {
            early.add(event);
            return;
        }
        events.map(event).ifPresent(this::send);
    }

    private void send(AgentEvent event)
    {
        queue.execute(() -> {
            try {
                listener.accept(event);
            }
            catch (RuntimeException failure) {
                log.log(System.Logger.Level.WARNING, "A turn listener failed", failure);
            }
        });
    }

    void finish(AgentTurnResult outcome)
    {
        Duration elapsed = outcome.elapsed();
        String reason = outcome.terminalReason().orElse("The turn failed");
        finish(switch (outcome.state()) {
            case COMPLETED -> new TurnResult.Completed(outcome.finalResponse().orElse(""), usage(), elapsed);
            // The drivers also stop a turn themselves, for its time limit, a lost process or an expired request; only the caller's stop is an interrupt.
            case CANCELLED, INTERRUPTED -> interruptRequested()
                    ? new TurnResult.Interrupted(elapsed)
                    : new TurnResult.Failed(timeLimitReached() ? TurnResult.Failure.TIME_LIMIT : TurnResult.Failure.PROVIDER_ERROR, reason, false, elapsed);
            case FAILED -> new TurnResult.Failed(failure(outcome), reason, outcome.transientFailure(), elapsed);
        });
    }

    /** The turn could not start or its provider failed outside a result; it never completes exceptionally. */
    void fail(Throwable failure)
    {
        Duration elapsed = Duration.between(started, Instant.now());
        if (interruptRequested()) {
            finish(new TurnResult.Interrupted(elapsed));
            return;
        }
        Throwable cause = Throwables.getRootCause(failure);
        boolean uncertain = Throwables.getCausalChain(failure).stream().anyMatch(AgentDeliveryUncertainException.class::isInstance);
        boolean sent;
        synchronized (this) {
            sent = running.isPresent();
        }
        finish(new TurnResult.Failed(
                uncertain ? TurnResult.Failure.DELIVERY_UNCERTAIN : sent ? TurnResult.Failure.PROVIDER_ERROR : TurnResult.Failure.NOT_SENT,
                Optional.ofNullable(failure.getMessage()).or(() -> Optional.ofNullable(cause.getMessage())).orElse(cause.getClass().getSimpleName()),
                false,
                elapsed));
    }

    private void finish(TurnResult outcome)
    {
        synchronized (this) {
            if (ended) {
                return;
            }
            ended = true;
            notifyAll();
        }
        queue.execute(() -> result.complete(outcome));
    }

    private synchronized Usage usage()
    {
        return events.rootUsage();
    }

    private TurnResult.Failure failure(AgentTurnResult outcome)
    {
        if (outcome.failureKind().filter(AgentTurnFailureKind.AUTHENTICATION_REQUIRED::equals).isPresent()) {
            return TurnResult.Failure.SIGN_IN_REQUIRED;
        }
        // ponytail: the drivers tag only sign-in; a usage limit or a lost process stays a provider error until they say so.
        return timeLimitReached() ? TurnResult.Failure.TIME_LIMIT : TurnResult.Failure.PROVIDER_ERROR;
    }

    /** Measured from this turn's start, before the driver's own timer starts, so a turn its driver stopped for the limit has reached it. */
    private boolean timeLimitReached()
    {
        return timeLimit.filter(limit -> Duration.between(started, Instant.now()).compareTo(limit) >= 0).isPresent();
    }

    static List<AgentAttachment> attachments(Input input)
    {
        return input.attachments().stream()
                .map(attachment -> new AgentAttachment(UUID.randomUUID().toString(), attachment.file().getFileName().toString(), attachment.mediaType(),
                        attachment.file().toAbsolutePath()))
                .collect(toImmutableList());
    }
}

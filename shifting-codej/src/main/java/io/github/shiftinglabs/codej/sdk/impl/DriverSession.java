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

import io.github.shiftinglabs.codej.AgentEvent;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.Turn;
import io.github.shiftinglabs.codej.TurnOptions;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * One conversation over a driver. A driver session may take one turn or, when the driver parks its process, more; each turn reuses a
 * parked one or resumes the conversation in a new one, as the host application's turn service does.
 */
final class DriverSession
        implements Session
{
    private static final System.Logger log = System.getLogger(DriverSession.class.getName());

    private final DriverAgent agent;
    private final AgentDriver driver;
    private final SessionOptions options;
    private final String id = UUID.randomUUID().toString();
    /** Turn ids start with the session's, so a process a turn owns leads back to the session's working directory after a crash. */
    private final AtomicInteger turns = new AtomicInteger();
    private final Path directory;
    private final AgentPermissionScope scope;
    private final Approvals approvals;
    private final boolean asksUser;

    // Guarded by this.
    private Optional<AgentSession> open = Optional.empty();
    /** The settings the open driver session was opened with, while it has not run a turn. */
    private Optional<AgentSessionContext> unused = Optional.empty();
    private AgentSessionRef reference;
    private volatile Optional<DriverTurn> current = Optional.empty();
    private boolean closed;
    /** The driver session whose process could not be shown stopped, which ends this session; a later close tries the stop again. */
    private Optional<AgentSession> unstopped = Optional.empty();
    /** Whether the provider has saved the conversation, as a driver session that ended may have told before it closed. */
    private boolean saved;

    private DriverSession(DriverAgent agent, SessionOptions options)
    {
        this.agent = requireNonNull(agent, "agent is null");
        this.driver = agent.driver();
        this.options = requireNonNull(options, "options is null");
        this.directory = agent.state().sessionDirectory(id, options.workingDirectory());
        SessionAccess access = switch (options.access()) {
            case TOOLS_ONLY -> options.tools().isEmpty() ? SessionAccess.TEXT_ONLY : SessionAccess.TOOLS_ONLY;
            case READ_ONLY -> SessionAccess.READ_ONLY;
            case WORKSPACE_WRITE -> SessionAccess.FULL;
        };
        this.scope = agent.sandbox().apply(new AgentPermissionScope(AgentPermissionPolicy.AUTO, access, false)
                .withNetwork(options.network())
                .withWebSearch(options.webSearch())
                .withCommits(options.commits())
                .withNoSubagents(options.subagents() == SessionOptions.Subagents.NONE)
                .withReadOnlySubagents(options.subagents() == SessionOptions.Subagents.READ_ONLY)
                .withApproveEveryCommand(options.approveEveryCommand())
                .withApproveEveryEdit(options.approveEveryEdit())
                .withFixedSandbox(options.fixedSandbox())
                .withWritableRoots(options.writableRoots())
                .withSessionDenials(options.deniedCommands(), options.deniedReads().stream().map(Path::toString).collect(toImmutableList())));
        this.approvals = new Approvals(options.approvals(), options.questions(), options.approvalTimeout(), options.workingDirectory(), agent.callbacks());
        // A tools-only session has none of the provider's own tools, the question tool among them.
        this.asksUser = options.questions().isPresent() && (access == SessionAccess.FULL || access == SessionAccess.READ_ONLY);
    }

    static DriverSession start(DriverAgent agent, SessionOptions options, Optional<AgentSessionRef> resume)
    {
        DriverSession session = new DriverSession(agent, options);
        try {
            synchronized (session) {
                AgentSessionContext context = session.context(resume, session.withDefault(options.model(), options.effort()), options.effort());
                session.openDriverSession(context);
                session.unused = Optional.of(context);
            }
            return session;
        }
        catch (RuntimeException failure) {
            // A process not shown stopped keeps its session's folder, so the next run's recovery can name the directory it works in.
            if (session.unstopped.isEmpty()) {
                StateFolder.delete(session.directory);
            }
            throw agentException(failure);
        }
    }

    @Override
    public synchronized boolean keepsProcess()
    {
        return current.map(DriverTurn::done).orElse(true) && open.filter(AgentSession::retained).isPresent();
    }

    @Override
    public synchronized Optional<SessionRef> ref()
    {
        boolean resumable = saved || open.map(AgentSession::resumable).orElse(false);
        return resumable ? Optional.of(ref(agent.provider(), reference)) : Optional.empty();
    }

    /** A driver's conversation as the API names it: its contract version and provider id, for its provider. */
    static SessionRef ref(String provider, AgentSessionRef reference)
    {
        return new SessionRef(provider, reference.contractVersion() + ":" + reference.providerValue());
    }

    static AgentSessionRef reference(AgentLaneId lane, String provider, SessionRef ref)
    {
        checkArgument(ref.provider().equals(provider), "This conversation belongs to %s, not %s", ref.provider(), provider);
        int separator = ref.id().indexOf(':');
        checkArgument(separator > 0, "Not a conversation of this library: %s", ref.id());
        try {
            return new AgentSessionRef(lane, Integer.parseInt(ref.id().substring(0, separator)), ref.id().substring(separator + 1));
        }
        catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Not a conversation of this library: " + ref.id(), failure);
        }
    }

    @Override
    public Turn start(Input input, TurnOptions turnOptions, Consumer<AgentEvent> listener)
    {
        requireNonNull(input, "input is null");
        requireNonNull(turnOptions, "turnOptions is null");
        requireNonNull(listener, "listener is null");
        var capabilities = driver.capabilities();
        input.attachments().forEach(attachment -> checkArgument(capabilities.attachmentMediaTypes().contains(attachment.mediaType()),
                "This agent does not take %s attachments", attachment.mediaType()));
        DriverTurn turn;
        synchronized (this) {
            checkState(!closed, "The session is closed");
            checkState(unstopped.isEmpty(), "The session's process could not be shown to have stopped, so it takes no more turns");
            checkState(current.map(DriverTurn::done).orElse(true), "Another turn is running");
            // A resumed conversation keeps its provider id, so the cost it counts across resumes splits by it.
            String conversation = reference.providerValue();
            turn = new DriverTurn(listener, agent.callbacks(), capabilities.steering(), turnOptions.timeLimit(), total -> agent.state().costs().turnCost(conversation, total));
            current = Optional.of(turn);
        }
        // On the caller's thread, as the drivers' own start runs: a caller that holds a lock its approval handler needs is not stuck behind it.
        run(turn, input, turnOptions);
        return turn;
    }

    private void run(DriverTurn turn, Input input, TurnOptions turnOptions)
    {
        try {
            Optional<String> effort = turnOptions.effort().or(options::effort);
            Optional<String> model = withDefault(turnOptions.model().or(options::model), effort);
            AgentSession session = prepare(model, effort);
            RunningTurn running = session.start(new AgentTurnCommand(id + "-" + turns.incrementAndGet(), input.text(), DriverTurn.attachments(input),
                    turnOptions.timeLimit().orElse(Duration.ZERO), model, effort.filter(_ -> model.isPresent()), turnOptions.serviceTier()));
            turn.running(running);
            // Off the driver's thread: settling takes this session's lock, which a closing caller holds while the driver stops.
            running.completion().whenCompleteAsync((outcome, failure) -> {
                settle(session, failure != null || !outcome.providerQuiescent());
                if (failure != null) {
                    turn.fail(failure instanceof CompletionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failure);
                }
                else {
                    turn.finish(outcome);
                }
            }, agent.settling());
        }
        catch (RuntimeException failure) {
            turn.fail(failure);
        }
    }

    /** The driver session for the next turn: the open one when it can take it, otherwise the conversation resumed in a new one. */
    private synchronized AgentSession prepare(Optional<String> model, Optional<String> effort)
    {
        checkState(!closed, "The session is closed");
        AgentSessionContext next = context(Optional.of(reference), model, effort);
        if (open.isPresent()) {
            AgentSession session = open.orElseThrow();
            boolean fresh = unused.filter(context -> context.model().equals(next.model()) && context.effort().equals(next.effort())).isPresent();
            unused = Optional.empty();
            if (fresh || session.prepareTurn(next, this::emit)) {
                return session;
            }
            closeDriverSession();
        }
        return openDriverSession(next);
    }

    /**
     * A driver session that will not take another turn is closed now, so its process and tool token go with the turn, as is one whose
     * turn left work it could not show stopped. A process that cannot be shown stopped ends this session, and leaves its working
     * directory unconfirmed. The turn reports its result only after this.
     */
    private synchronized void settle(AgentSession session, boolean unsettled)
    {
        if (open.equals(Optional.of(session)) && (unsettled || !session.retained())) {
            try {
                closeDriverSession();
            }
            catch (RuntimeException failure) {
                log.log(System.Logger.Level.WARNING, "Could not confirm that a finished turn's process stopped", failure);
                agent.unconfirmed(this);
            }
        }
    }

    private AgentSession openDriverSession(AgentSessionContext context)
    {
        AgentSession session = driver.open(context, this::emit);
        try {
            session.allowReuse();
            reference = session.reference();
        }
        catch (RuntimeException failure) {
            // Its process may have started, so it goes with the failed open.
            try {
                session.close();
            }
            catch (RuntimeException closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
                unstopped = Optional.of(session);
                agent.unconfirmed(this);
            }
            throw failure;
        }
        open = Optional.of(session);
        return session;
    }

    private void closeDriverSession()
    {
        Optional<AgentSession> session = open;
        open = Optional.empty();
        saved |= session.map(AgentSession::resumable).orElse(false);
        try {
            session.ifPresent(AgentSession::close);
        }
        catch (RuntimeException failure) {
            unstopped = session;
            throw failure;
        }
    }

    Path workingDirectory()
    {
        return options.workingDirectory();
    }

    private void emit(ProviderAgentEvent event)
    {
        current.ifPresent(turn -> turn.deliver(event));
    }

    /** The drivers need a model to set an effort; the agent's default model takes it when the caller names none. */
    private Optional<String> withDefault(Optional<String> model, Optional<String> effort)
    {
        return model.or(() -> effort.flatMap(_ -> agent.defaultModel()));
    }

    private AgentSessionContext context(Optional<AgentSessionRef> resume, Optional<String> model, Optional<String> effort)
    {
        return new AgentSessionContext(
                id,
                options.workingDirectory(),
                directory,
                directory.resolve("provider"),
                scope,
                resume,
                approvals,
                model,
                effort.filter(_ -> model.isPresent()),
                options.instructions().orElse(""),
                options.tools(),
                asksUser);
    }

    @Override
    public void close()
    {
        Optional<DriverTurn> running;
        Optional<AgentSession> earlier;
        synchronized (this) {
            if (closed && unstopped.isEmpty()) {
                return;
            }
            closed = true;
            approvals.close();
            running = current.filter(turn -> !turn.done());
            earlier = unstopped;
        }
        running.ifPresent(DriverTurn::interrupt);
        boolean failedNow;
        try {
            synchronized (this) {
                // The interrupted turn's own stop just failed, so this close is that attempt; a later one tries again.
                failedNow = unstopped.isPresent() && earlier.isEmpty();
                if (!failedNow) {
                    // A stop that failed in an earlier close is tried again; the driver checks the process once more.
                    earlier.ifPresent(AgentSession::close);
                    unstopped = Optional.empty();
                    closeDriverSession();
                }
            }
        }
        catch (RuntimeException failure) {
            agent.unconfirmed(this);
            // A process not shown stopped keeps its session's folder, so the next run's recovery can name the directory it works in.
            throw new AgentException("The session's process could not be shown to have exited", failure);
        }
        finally {
            agent.forget(this);
        }
        if (failedNow) {
            throw new AgentException("The session's process could not be shown to have exited");
        }
        agent.stopped(this);
        StateFolder.delete(directory);
    }

    static AgentException agentException(Throwable failure)
    {
        return failure instanceof AgentException agentFailure ? agentFailure : new AgentException(message(failure), failure);
    }

    static String message(Throwable failure)
    {
        return Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
    }
}

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.github.shiftinglabs.codej.Access;
import io.github.shiftinglabs.codej.AgentEvent;
import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.AgentStatus;
import io.github.shiftinglabs.codej.Approval;
import io.github.shiftinglabs.codej.ApprovalHandler;
import io.github.shiftinglabs.codej.Decision;
import io.github.shiftinglabs.codej.DeliveryUncertainException;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Limits;
import io.github.shiftinglabs.codej.Model;
import io.github.shiftinglabs.codej.Question;
import io.github.shiftinglabs.codej.QuestionHandler;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.Source;
import io.github.shiftinglabs.codej.Turn;
import io.github.shiftinglabs.codej.TurnOptions;
import io.github.shiftinglabs.codej.TurnResult;
import io.github.shiftinglabs.codej.Usage;
import io.github.shiftinglabs.codej.cli.driver.AgentDeliveryUncertainException;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalScope;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaSnapshot;
import io.github.shiftinglabs.codej.cli.driver.ProviderQuotaWindow;
import io.github.shiftinglabs.codej.cli.driver.ProviderServiceTier;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDriverAgent
{
    private static final List<ProviderAgentEvent.CommandAction> LISTING = ImmutableList.of(
            new ProviderAgentEvent.CommandAction(ProviderAgentEvent.CommandActionKind.LIST_FILES, Optional.empty(), Optional.of("."), Optional.empty()));
    private static final AgentEvent.Action LISTED = new AgentEvent.Action(AgentEvent.ActionKind.LIST_FILES, Optional.empty(), Optional.of(Path.of(".")), Optional.empty());

    @TempDir
    private Path state;
    @TempDir
    private Path checkout;

    private final FakeDriver driver = new FakeDriver();
    private DriverAgent agent;

    @BeforeEach
    void agent()
    {
        agent = new DriverAgent(driver, "codex", StateFolder.open(state), new PermissionBroker(new ObjectMapper()), command -> Thread.ofVirtual().start(command),
                scope -> scope);
    }

    @AfterEach
    void close()
    {
        agent.close();
    }

    @Test
    void theFirstSessionWaitsForACheckOfTheCli()
    {
        driver.status = new AgentProviderStatus(AgentLaneId.CODEX_CLI, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, Optional.of("1.0"), "Sign in",
                FakeDriver.defaultCapabilities(), Optional.empty());
        assertThatThrownBy(() -> agent.startSession(SessionOptions.in(checkout).build())).isInstanceOf(AgentException.class).hasMessage("Sign in");
        assertThat(driver.sessions).isEmpty();

        driver.status = new AgentProviderStatus(AgentLaneId.CODEX_CLI, AgentProviderStatus.Readiness.READY, Optional.of("1.0"), "Signed in",
                FakeDriver.defaultCapabilities(), Optional.empty());
        agent.startSession(SessionOptions.in(checkout).build()).close();
        agent.startSession(SessionOptions.in(checkout).build()).close();
        assertThat(driver.probes).hasValue(2);
    }

    @Test
    void statusAndModelsComeFromTheDriver()
    {
        assertThat(agent.status().readiness()).isEqualTo(AgentStatus.Readiness.READY);
        assertThat(agent.status().capabilities().attachmentTypes()).containsExactly("image/png");

        driver.status = new AgentProviderStatus(AgentLaneId.CODEX_CLI, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, Optional.of("1.0"), "Sign in",
                FakeDriver.defaultCapabilities(), Optional.of("codex login"));
        AgentStatus signedOut = agent.status();
        assertThat(signedOut.readiness()).isEqualTo(AgentStatus.Readiness.SIGN_IN_REQUIRED);
        assertThat(signedOut.signInCommand()).contains("codex login");
        assertThat(signedOut.detail()).isEqualTo("Sign in");

        driver.status = new AgentProviderStatus(AgentLaneId.CODEX_CLI, AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE, Optional.of("1.0"), "Busy",
                FakeDriver.defaultCapabilities(), Optional.empty());
        assertThat(agent.status().readiness()).isEqualTo(AgentStatus.Readiness.TEMPORARILY_UNAVAILABLE);

        driver.models = ImmutableList.of(
                new ProviderModel("m1", "Model One", "Fast", ImmutableList.of("low", "high"), Optional.of("low"), true, ImmutableList.of("text", "image"),
                        ImmutableList.of(new ProviderServiceTier("priority", "Fast", "Faster processing"))),
                new ProviderModel("m2", "Model Two", "Text", ImmutableList.of(), Optional.empty(), false, ImmutableList.of("text"), ImmutableList.of()));
        assertThat(agent.models()).containsExactly(
                new Model("m1", "Model One", "Fast", ImmutableList.of("low", "high"), Optional.of("low"), true,
                        ImmutableList.of(new Model.ServiceTier("priority", "Fast", "Faster processing")), true),
                new Model("m2", "Model Two", "Text", ImmutableList.of(), Optional.empty(), false, ImmutableList.of(), false));
    }

    @Test
    void limitsComeFromTheDriversQuota()
    {
        Instant start = Instant.parse("2026-11-09T10:00:00Z");
        Instant reset = start.plus(Duration.ofHours(5));
        driver.quota = new ProviderQuotaSnapshot(Optional.of("Pro"), ImmutableList.of(new ProviderQuotaWindow("five-hour", "5 hours", 42.5, Optional.of(start),
                Optional.of(reset), Optional.of(new ProviderQuotaWindow.Amount(new BigDecimal("1.50"), BigDecimal.TEN, "USD")))), Optional.empty());
        assertThat(agent.limits()).isEqualTo(new Limits(Optional.of("Pro"), ImmutableList.of(new Limits.Window("five-hour", "5 hours", 42.5, Optional.of(start),
                Optional.of(reset), Optional.of(new Limits.Amount(new BigDecimal("1.50"), BigDecimal.TEN, "USD")))), Optional.empty()));

        driver.quotaFailure = Optional.of(new AgentUnavailableException("Codex did not answer"));
        assertThat(agent.limits()).isEqualTo(new Limits(Optional.empty(), ImmutableList.of(), Optional.of("Codex did not answer")));
    }

    @Test
    void aTurnDeliversItsEventsInOrderThenItsResult()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        List<AgentEvent> seen = new CopyOnWriteArrayList<>();
        Turn turn = session.start(Input.text("Say hello"), event -> {
            sleepBriefly();
            seen.add(event);
        });
        FakeDriver.FakeSession.FakeTurn running = running(driver.last());
        assertThat(running.command.message()).isEqualTo("Say hello");

        emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.PARTIAL, "Hel", ProviderAgentEventSource.item("answer")));
        emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.PARTIAL, "lo", ProviderAgentEventSource.item("answer")));
        emit(new ProviderAgentEvent.Command(Optional.empty(), ProviderAgentEvent.ActivityState.STARTED, ImmutableList.of("ls", "-la"), Optional.empty(),
                Optional.empty(), false, ProviderAgentEventSource.item("ls"), Optional.of("."), Optional.empty(), LISTING, false, Optional.of("List the folder")));
        emit(new ProviderAgentEvent.Command(Optional.empty(), ProviderAgentEvent.ActivityState.PROGRESS, ImmutableList.of(), Optional.of("a\n"), Optional.empty(), false,
                ProviderAgentEventSource.item("ls")));
        emit(new ProviderAgentEvent.Command(Optional.empty(), ProviderAgentEvent.ActivityState.COMPLETED, ImmutableList.of(), Optional.of("a\nb\n"), Optional.of(0), true,
                ProviderAgentEventSource.item("ls"), Optional.empty(), Optional.of(Duration.ofSeconds(2)), ImmutableList.of()));
        emit(new ProviderAgentEvent.Tool(Optional.empty(), "read_issue", ProviderAgentEvent.ActivityState.COMPLETED, "{\"key\":\"ENG-1\"}",
                ProviderAgentEventSource.item("call"), Optional.empty(), Optional.of("Read ENG-1"), Optional.of(new ProviderAgentEvent.ToolOutput("read_issue", "ENG-1: Fix it"))));
        emit(new ProviderAgentEvent.Usage(new ProviderUsageSample(Optional.empty(), ProviderUsageSample.Semantics.CUMULATIVE_FOR_INVOCATION, 10, 2, 5, Optional.empty())));
        emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.TURN_STARTED, "started"));
        emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessagePhase.FINAL, "Hello", ProviderAgentEventSource.item("answer")));
        running.complete("Hello");

        TurnResult result = turn.await();
        assertThat(result).isInstanceOf(TurnResult.Completed.class);
        assertThat(((TurnResult.Completed) result).finalText()).isEqualTo("Hello");
        assertThat(((TurnResult.Completed) result).usage()).isEqualTo(new Usage(10, 2, 5, Optional.empty()));
        // The result completes only after the listener returned for the last event.
        assertThat(seen).containsExactly(
                // First, that the provider took the turn.
                new AgentEvent.Started(Source.root()),
                new AgentEvent.MessageDelta(Source.root(), "answer", AgentEvent.MessageKind.ANSWER, "Hel"),
                new AgentEvent.MessageDelta(Source.root(), "answer", AgentEvent.MessageKind.ANSWER, "lo"),
                // Later events keep what the first one told about the command.
                new AgentEvent.Command(Source.root(), "ls", "ls -la", AgentEvent.Activity.STARTED, "", false, OptionalInt.empty(), Optional.of(Path.of(".")),
                        ImmutableList.of(LISTED), Optional.of("List the folder"), false, Optional.empty()),
                new AgentEvent.Command(Source.root(), "ls", "ls -la", AgentEvent.Activity.PROGRESS, "a\n", false, OptionalInt.empty(), Optional.of(Path.of(".")),
                        ImmutableList.of(LISTED), Optional.of("List the folder"), false, Optional.empty()),
                new AgentEvent.Command(Source.root(), "ls", "ls -la", AgentEvent.Activity.COMPLETED, "a\nb\n", true, OptionalInt.of(0), Optional.of(Path.of(".")),
                        ImmutableList.of(LISTED), Optional.of("List the folder"), false, Optional.of(Duration.ofSeconds(2))),
                new AgentEvent.ToolCall(Source.root(), "call", "read_issue", AgentEvent.Activity.COMPLETED, Optional.of("{\"key\":\"ENG-1\"}"), Optional.of("Read ENG-1"),
                        Optional.of("ENG-1: Fix it")),
                new AgentEvent.UsageUpdate(Source.root(), new Usage(10, 2, 5, Optional.empty())),
                new AgentEvent.Message(Source.root(), "answer", AgentEvent.MessageKind.ANSWER, "Hello"));
        assertThat(turn.result().toCompletableFuture().complete(new TurnResult.Interrupted(Duration.ZERO))).isFalse();
        assertThat(turn.await()).isEqualTo(result);
    }

    @Test
    void subagentsPlansAndNoticesArriveAsTheirOwnEvents()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        List<AgentEvent> seen = new CopyOnWriteArrayList<>();
        Turn turn = session.start(Input.text("Look around"), seen::add);
        FakeDriver.FakeSession.FakeTurn running = running(driver.last());
        ProviderAgentEventSource child = new ProviderAgentEventSource(Optional.of("child"), Optional.of("root-thread"), Optional.of("explorer"), Optional.empty(),
                Optional.empty());

        emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessageKind.PROMPT, ProviderAgentEvent.MessagePhase.FINAL, "Look around",
                ProviderAgentEventSource.item("prompt")));
        emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessageKind.PLAN_TEXT, ProviderAgentEvent.MessagePhase.FINAL, "1. Read the README",
                ProviderAgentEventSource.item("plan")));
        emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.AGENT_STARTED, "explorer", child));
        emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.BACKFILL_STARTED, "", child));
        emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessageKind.PROMPT, ProviderAgentEvent.MessagePhase.FINAL, "Find the tests",
                child.withItem(Optional.of("asked"))));
        emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.BACKFILL_COMPLETED, "", child));
        // A partial message with an event id is a whole one, as Codex marks a finished commentary item.
        emit(new ProviderAgentEvent.Message(Optional.of("note:completed"), ProviderAgentEvent.MessageKind.COMMENTARY, ProviderAgentEvent.MessagePhase.PARTIAL,
                "Looking at the tests", ProviderAgentEventSource.item("note")));
        // A subagent the provider names only by its parent is still not the root.
        emit(new ProviderAgentEvent.Message(Optional.empty(), ProviderAgentEvent.MessageKind.ANSWER, ProviderAgentEvent.MessagePhase.FINAL, "Notes",
                new ProviderAgentEventSource(Optional.empty(), Optional.of("root-thread"), Optional.empty(), Optional.empty(), Optional.of("notes"))));
        emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.RETRYING, "Retrying in 2 s"));
        emit(new ProviderAgentEvent.Tool(Optional.empty(), ProviderAgentEvent.Tool.CONTEXT_COMPACTION, ProviderAgentEvent.ActivityState.STARTED, "",
                ProviderAgentEventSource.item("compact")));
        emit(new ProviderAgentEvent.Tool(Optional.empty(), ProviderAgentEvent.Tool.CONTEXT_COMPACTION, ProviderAgentEvent.ActivityState.COMPLETED, "",
                ProviderAgentEventSource.item("compact")));
        emit(new ProviderAgentEvent.Lifecycle(Optional.empty(), ProviderAgentEvent.LifecyclePhase.AGENT_COMPLETED, "explorer", child));
        running.complete("Done");
        turn.await();

        // The root's own prompt is the caller's input, so only the subagent's comes back.
        Source explorer = new Source(Optional.of("child"), Optional.of("root-thread"), Optional.of("explorer"));
        assertThat(seen).containsExactly(
                new AgentEvent.Started(Source.root()),
                new AgentEvent.Message(Source.root(), "plan", AgentEvent.MessageKind.PLAN, "1. Read the README"),
                new AgentEvent.Subagent(explorer, AgentEvent.Activity.STARTED),
                new AgentEvent.Replay(explorer, AgentEvent.Activity.STARTED),
                new AgentEvent.Message(explorer, "asked", AgentEvent.MessageKind.PROMPT, "Find the tests"),
                new AgentEvent.Replay(explorer, AgentEvent.Activity.COMPLETED),
                new AgentEvent.Message(Source.root(), "note", AgentEvent.MessageKind.COMMENTARY, "Looking at the tests"),
                new AgentEvent.Message(new Source(Optional.empty(), Optional.of("root-thread"), Optional.empty()), "notes", AgentEvent.MessageKind.ANSWER, "Notes"),
                new AgentEvent.Notice(Source.root(), AgentEvent.NoticeKind.RETRYING, "Retrying in 2 s"),
                new AgentEvent.Notice(Source.root(), AgentEvent.NoticeKind.CONTEXT_COMPACTED, ProviderAgentEvent.Tool.CONTEXT_COMPACTION),
                new AgentEvent.Subagent(explorer, AgentEvent.Activity.COMPLETED));
    }

    @Test
    void aTurnReusesAKeptProcessOrResumesTheConversationInANewOne()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        FakeDriver.FakeSession first = driver.last();

        driver.retains = true;
        complete(session.start(Input.text("one"), _ -> {}), first, 1);
        // The kept process owns the working directory until the next turn or the close.
        assertThat(session.keepsProcess()).isTrue();
        complete(session.start(Input.text("two"), _ -> {}), first, 2);
        assertThat(driver.sessions).hasSize(1);

        driver.retains = false;
        complete(session.start(Input.text("three"), _ -> {}), first, 3);
        assertThat(first.closed).isTrue();
        assertThat(session.keepsProcess()).isFalse();
        Turn fourth = session.start(Input.text("four"), _ -> {});
        eventually(() -> driver.sessions.size() == 2);
        FakeDriver.FakeSession resumed = driver.last();
        assertThat(resumed.context.sessionReference()).contains(first.reference);
        complete(fourth, resumed, 1);
        assertThat(session.ref()).contains(new SessionRef("codex", "3:" + first.reference.providerValue()));
    }

    @Test
    void aConversationsCostSplitsIntoItsTurnsAcrossResumes()
            throws Exception
    {
        driver.retains = true;
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        assertThat(turnCost(session, 1, "0.30")).contains(new BigDecimal("0.30"));
        assertThat(turnCost(session, 2, "0.50")).contains(new BigDecimal("0.20"));
        SessionRef ref = session.ref().orElseThrow();
        session.close();
        // The provider's total runs on across the resume, so the resumed turn costs what it grew by.
        try (Session resumed = agent.resumeSession(ref, SessionOptions.in(checkout).build())) {
            assertThat(turnCost(resumed, 1, "0.65")).contains(new BigDecimal("0.15"));
        }
    }

    @Test
    void aTurnThatLeavesWorkRunningStopsItsProcessFirst()
            throws Exception
    {
        driver.retains = true;
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        FakeDriver.FakeSession first = driver.last();
        Turn turn = session.start(Input.text("Start the server"), _ -> {});
        eventually(() -> first.turns.size() == 1);
        first.turn().completeUnsettled("Started");
        assertThat(turn.await()).isInstanceOf(TurnResult.Completed.class);
        // A kept process takes the next turn, but not one whose work could not be shown stopped.
        assertThat(first.closed).isTrue();
        assertThat(agent.unconfirmedDirectories()).isEmpty();
        session.close();
    }

    @Test
    void aSessionWhoseProcessCannotBeShownStoppedEnds()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        FakeDriver.FakeSession first = driver.last();
        driver.stopFails = true;
        Turn turn = session.start(Input.text("Start the server"), _ -> {});
        eventually(() -> first.turns.size() == 1);
        first.turn().completeUnsettled("Started");
        turn.await();
        assertThat(agent.unconfirmedDirectories()).containsExactly(checkout.toAbsolutePath());
        assertThatThrownBy(() -> session.start(Input.text("Again"), _ -> {})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(session::close).isInstanceOf(AgentException.class);
        assertThatThrownBy(session::close).isInstanceOf(AgentException.class);
        assertThat(agent.unconfirmedDirectories()).containsExactly(checkout.toAbsolutePath());
        // Closing again checks the process once more; once it is shown stopped, the directory is free.
        driver.stopFails = false;
        session.close();
        assertThat(first.closed).isTrue();
        assertThat(agent.unconfirmedDirectories()).isEmpty();
        session.close();
    }

    @Test
    void aConversationHasARefOnlyOnceTheProviderSavedIt()
            throws Exception
    {
        driver.savesOnAnswer = true;
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        assertThat(session.ref()).isEmpty();
        complete(session.start(Input.text("one"), _ -> {}), driver.last(), 1);
        // The driver session closed with its turn, after telling that the conversation was saved.
        assertThat(driver.last().closed).isTrue();
        assertThat(session.ref()).contains(new SessionRef("codex", "3:" + driver.last().reference.providerValue()));
        session.close();
    }

    @Test
    void anotherTurnCannotStartWhileOneRuns()
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        session.start(Input.text("one"), _ -> {});
        assertThatThrownBy(() -> session.start(Input.text("two"), _ -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Another turn is running");
        assertThatThrownBy(() -> session.start(Input.text("three").withAttachment(checkout.resolve("a.pdf"), "application/pdf"), _ -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application/pdf");
    }

    @Test
    void interruptAndSteerReachTheProviderTurn()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        Turn turn = session.start(Input.text("work"), _ -> {});
        turn.steer(Input.text("also this"));
        FakeDriver.FakeSession.FakeTurn running = running(driver.last());
        assertThat(running.steered).singleElement().satisfies(input -> assertThat(input.message()).isEqualTo("also this"));

        // The driver's own failures arrive as the API's: maybe delivered, or refused.
        running.steerFailure = Optional.of(new AgentDeliveryUncertainException("The connection dropped", new IOException("reset")));
        assertThatThrownBy(() -> turn.steer(Input.text("maybe"))).isExactlyInstanceOf(DeliveryUncertainException.class).hasMessage("The connection dropped");
        running.steerFailure = Optional.of(new AgentUnavailableException("Codex refused the input"));
        assertThatThrownBy(() -> turn.steer(Input.text("no"))).isExactlyInstanceOf(AgentException.class).hasMessage("Codex refused the input");
        running.steerFailure = Optional.empty();

        assertThat(turn.interrupt().toCompletableFuture().get(5, TimeUnit.SECONDS)).isInstanceOf(TurnResult.Interrupted.class);
        assertThat(running.cancelled).isTrue();
        assertThatThrownBy(() -> turn.steer(Input.text("late"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTurnThatCannotStartEndsAsFailed()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        complete(session.start(Input.text("one"), _ -> {}), driver.last(), 1);
        driver.openFailure = Optional.of(new AgentUnavailableException("The conversation is gone"));
        List<AgentEvent> seen = new CopyOnWriteArrayList<>();
        TurnResult result = session.start(Input.text("two"), seen::add).await();
        // The provider never had it, so sending it again is safe.
        assertThat(result).isInstanceOfSatisfying(TurnResult.Failed.class, failed -> {
            assertThat(failed.failure()).isEqualTo(TurnResult.Failure.NOT_SENT);
            assertThat(failed.message()).isEqualTo("The conversation is gone");
        });
        assertThat(seen).isEmpty();
        session.close();
        assertThatThrownBy(() -> session.start(Input.text("three"), _ -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTurnTheProviderStoppedOnItsOwnFailed()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        Turn lost = session.start(Input.text("one"), _ -> {});
        running(driver.last()).end(AgentTurnResult.State.INTERRUPTED, "Codex disconnected");
        assertThat(lost.await()).isInstanceOfSatisfying(TurnResult.Failed.class, failed -> {
            assertThat(failed.failure()).isEqualTo(TurnResult.Failure.PROVIDER_ERROR);
            assertThat(failed.message()).isEqualTo("Codex disconnected");
        });

        Turn limited = session.start(Input.text("two"), TurnOptions.defaults().withTimeLimit(Duration.ofMillis(20)), _ -> {});
        eventually(() -> driver.sessions.size() == 2 && !driver.last().turns.isEmpty());
        Thread.sleep(30);
        driver.last().turn().end(AgentTurnResult.State.INTERRUPTED, "Codex turn timed out");
        assertThat(limited.await()).isInstanceOfSatisfying(TurnResult.Failed.class, failed -> assertThat(failed.failure()).isEqualTo(TurnResult.Failure.TIME_LIMIT));
    }

    @Test
    void closingTheSessionInterruptsItsTurnAndClosesTheProvider()
            throws Exception
    {
        Session session = agent.startSession(SessionOptions.in(checkout).build());
        Turn turn = session.start(Input.text("work"), _ -> {});
        FakeDriver.FakeSession.FakeTurn running = running(driver.last());
        session.close();
        assertThat(turn.await()).isInstanceOf(TurnResult.Interrupted.class);
        assertThat(running.cancelled).isTrue();
        assertThat(driver.last().closed).isTrue();
        session.close();
    }

    @Test
    void aSessionsSettingsReachItsDriver()
    {
        Path cache = state.resolve("cache");
        Path secrets = state.resolve("secrets");
        agent.startSession(SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).writableRoot(cache).denyCommand("git push").denyRead(secrets)
                .allowWebSearch().allowCommits().readOnlySubagents().approveEveryCommand().approveEveryEdit().fixedSandbox().build());
        AgentPermissionScope scope = driver.last().context.scope();
        assertThat(scope.writableRoots()).containsExactly(cache);
        assertThat(scope.deniedCommands()).contains("git push");
        assertThat(scope.deniedReads(ImmutableMap.of())).contains(secrets.toString());
        assertThat(scope.webSearch()).isTrue();
        assertThat(scope.commits()).isTrue();
        assertThat(scope.readOnlySubagents()).isTrue();
        assertThat(scope.noSubagents()).isFalse();
        assertThat(scope.approveEveryCommand()).isTrue();
        assertThat(scope.approveEveryEdit()).isTrue();
        assertThat(scope.fixedSandbox()).isTrue();

        agent.startSession(SessionOptions.in(checkout).noSubagents().build());
        scope = driver.last().context.scope();
        assertThat(scope.noSubagents()).isTrue();
        assertThat(scope.readOnlySubagents()).isFalse();
        assertThat(scope.webSearch()).isFalse();
        assertThat(scope.writableRoots()).isEmpty();
    }

    @Test
    void sessionsGetTheirAccessNetworkAndInstructions()
    {
        agent.startSession(SessionOptions.in(checkout).build());
        assertThat(driver.last().context.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(driver.last().context.scope().network()).isFalse();

        agent.startSession(SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).allowNetwork().instructions("Be brief").build());
        assertThat(driver.last().context.access()).isEqualTo(SessionAccess.FULL);
        assertThat(driver.last().context.scope().network()).isTrue();
        assertThat(driver.last().context.scope().commits()).isFalse();
        assertThat(driver.last().context.managedInstructions()).isEqualTo("Be brief");
        assertThat(driver.last().context.worktree()).isEqualTo(checkout.toAbsolutePath());

        agent.startSession(SessionOptions.in(checkout).access(Access.TOOLS_ONLY).build());
        assertThat(driver.last().context.access()).isEqualTo(SessionAccess.TEXT_ONLY);
    }

    @Test
    void anEffortWithoutAModelUsesTheDefaultModel()
    {
        driver.models = ImmutableList.of(
                new ProviderModel("other", "Other", "", ImmutableList.of("high"), Optional.empty(), false, ImmutableList.of(), ImmutableList.of()),
                new ProviderModel("main", "Main", "", ImmutableList.of("high"), Optional.empty(), true, ImmutableList.of(), ImmutableList.of()));
        agent.startSession(SessionOptions.in(checkout).effort("high").build());
        assertThat(driver.last().context.model()).contains("main");
        assertThat(driver.last().context.effort()).contains("high");
    }

    @Test
    void aSavedConversationResumesOnlyWithItsProvider()
    {
        String saved = agent.startSession(SessionOptions.in(checkout).build()).ref().orElseThrow().asString();
        AgentSessionRef first = driver.last().reference;

        agent.resumeSession(SessionRef.parse(saved), SessionOptions.in(checkout).build());
        assertThat(driver.last().context.sessionReference()).contains(first);

        assertThatThrownBy(() -> agent.resumeSession(new SessionRef("claude", "3:" + first.providerValue()), SessionOptions.in(checkout).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claude");
        assertThatThrownBy(() -> agent.resumeSession(new SessionRef("codex", "thread"), SessionOptions.in(checkout).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSessionThatFailsAsItOpensStopsItsProcess()
    {
        driver.referenceFailure = Optional.of(new IllegalStateException("No conversation id"));
        assertThatThrownBy(() -> agent.startSession(SessionOptions.in(checkout).build())).isInstanceOf(AgentException.class).hasMessageContaining("No conversation id");
        assertThat(driver.last().closed).isTrue();
        assertThat(agent.unconfirmedDirectories()).isEmpty();

        driver.stopFails = true;
        assertThatThrownBy(() -> agent.startSession(SessionOptions.in(checkout).build())).isInstanceOf(AgentException.class);
        // A process that may still run keeps its directory listed.
        assertThat(agent.unconfirmedDirectories()).containsExactly(checkout.toAbsolutePath());
        driver.stopFails = false;
        driver.referenceFailure = Optional.empty();
    }

    @Test
    void aClosedSessionAsksNothing()
            throws Exception
    {
        AtomicReference<Approval> asked = new AtomicReference<>();
        Session session = agent.startSession(SessionOptions.in(checkout).approvals(approval -> {
            asked.set(approval);
            return CompletableFuture.completedStage(Decision.ALLOW_ONCE);
        }).build());
        AgentSessionContext context = driver.last().context;
        session.close();
        // A late request from the stopped process belongs to no turn.
        ProviderApprovalDecision late = context.approvals().request(command("ls"), Duration.ofMinutes(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(late.decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);
        assertThat(asked.get()).isNull();
    }

    @Test
    void approvalsAskTheHandlerAndDenyAnythingElse()
            throws Exception
    {
        AtomicReference<Approval> asked = new AtomicReference<>();
        Approvals allowing = new Approvals(approval -> {
            asked.set(approval);
            return CompletableFuture.completedStage(Decision.ALLOW_ONCE);
        }, Optional.empty(), Duration.ofMinutes(1), checkout, Runnable::run);
        assertThat(decide(allowing, command("git status")).decision()).isEqualTo(ProviderApprovalDecision.Decision.ALLOW);
        assertThat(asked.get()).isEqualTo(new Approval.Command(Source.root(), "Allow command", ImmutableSet.of(Decision.Kind.ALLOW_ONCE, Decision.Kind.DENY), "git status",
                checkout, false, ImmutableList.of("github.com")));

        Approvals refusing = new Approvals(_ -> CompletableFuture.completedStage(Decision.deny("Use ./mvnw instead")), Optional.empty(), Duration.ofMinutes(1), checkout,
                Runnable::run);
        ProviderApprovalDecision refused = decide(refusing, command("mvn verify"));
        assertThat(refused.decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);
        assertThat(refused.reason()).contains("Use ./mvnw instead");

        Approvals throwing = new Approvals(_ -> {
            throw new IllegalStateException("broken");
        }, Optional.empty(), Duration.ofMinutes(1), checkout, Runnable::run);
        assertThat(decide(throwing, command("ls")).decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);

        CompletableFuture<Decision> unanswered = new CompletableFuture<>();
        Approvals silent = new Approvals(_ -> unanswered, Optional.empty(), Duration.ofMillis(50), checkout, Runnable::run);
        ProviderApprovalDecision late = decide(silent, command("ls"));
        assertThat(late.decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);
        // A denial, not an expiry: the agent is told and carries on.
        assertThat(late.timedOut()).isFalse();
        // The handler learns that nobody needs its answer any more.
        eventually(unanswered::isCancelled);

        CompletableFuture<Decision> pending = new CompletableFuture<>();
        Approvals waiting = new Approvals(_ -> pending, Optional.empty(), Duration.ofMinutes(1), checkout, Runnable::run);
        waiting.request(command("ls"), Duration.ofMinutes(5)).toCompletableFuture().cancel(false);
        // A request the provider settled itself withdraws the handler's too.
        eventually(pending::isCancelled);

        Approvals outside = new Approvals(_ -> CompletableFuture.completedStage(Decision.ALLOW_FOR_SESSION), Optional.empty(), Duration.ofMinutes(1), checkout, Runnable::run);
        assertThat(decide(outside, command("ls")).decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);

        AtomicReference<Approval> question = new AtomicReference<>();
        Approvals noQuestions = new Approvals(approval -> {
            question.set(approval);
            return CompletableFuture.completedStage(Decision.ALLOW_ONCE);
        }, Optional.empty(), Duration.ofMinutes(1), checkout, Runnable::run);
        assertThat(decide(noQuestions, new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Ask", "{}", ImmutableList.of(ProviderApprovalRequest.QUESTION_TOOL),
                ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), false, ProviderAgentEventSource.root(), Instant.now()))
                .decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);
        assertThat(question.get()).isNull();
    }

    @Test
    void failedOrNullHandlerAnswersAreDenied()
            throws Exception
    {
        for (ApprovalHandler handler : ImmutableList.<ApprovalHandler>of(
                _ -> CompletableFuture.failedStage(new IllegalStateException("broken")),
                _ -> CompletableFuture.completedStage(null),
                _ -> null)) {
            Approvals approvals = new Approvals(handler, Optional.empty(), Duration.ofMinutes(1), checkout, Runnable::run);
            assertThat(decide(approvals, command("ls"))).isEqualTo(ProviderApprovalDecision.deny());
        }
        for (QuestionHandler handler : ImmutableList.<QuestionHandler>of(
                _ -> CompletableFuture.failedStage(new IllegalStateException("broken")),
                _ -> CompletableFuture.completedStage(null),
                _ -> null)) {
            Approvals approvals = new Approvals(ApprovalHandler.DENY_ALL, Optional.of(handler), Duration.ofMinutes(1), checkout, Runnable::run);
            assertThat(decide(approvals, question())).isEqualTo(ProviderApprovalDecision.deny());
        }
    }

    @Test
    void questionsGoToTheQuestionHandlerKeyedByTheirText()
            throws Exception
    {
        AtomicReference<Question> asked = new AtomicReference<>();
        Approvals answering = new Approvals(ApprovalHandler.DENY_ALL, Optional.of(question -> {
            asked.set(question);
            return CompletableFuture.completedStage(ImmutableMap.of("Which fruit?", ImmutableList.of("Banana"), "Which colors?", ImmutableList.of("Red", "Blue")));
        }), Duration.ofMinutes(1), checkout, Runnable::run);
        ProviderApprovalDecision decision = decide(answering, question());
        assertThat(decision.decision()).isEqualTo(ProviderApprovalDecision.Decision.ALLOW);
        assertThat(decision.answers()).containsExactlyInAnyOrderEntriesOf(ImmutableMap.of("Which fruit?", "Banana", "Which colors?", "Red, Blue"));
        assertThat(asked.get().items()).containsExactly(
                new Question.Item("Which fruit?", "Fruit", "Which fruit?", ImmutableList.of(new Question.Option("Apple", "Red"), new Question.Option("Banana", "Yellow")), false),
                new Question.Item("Which colors?", "Colors", "Which colors?", ImmutableList.of(new Question.Option("Red", ""), new Question.Option("Blue", "")), true));

        CompletableFuture<Map<String, List<String>>> unanswered = new CompletableFuture<>();
        Approvals silent = new Approvals(ApprovalHandler.DENY_ALL, Optional.of(_ -> unanswered), Duration.ofMillis(50), checkout, Runnable::run);
        assertThat(decide(silent, question()).decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);
        eventually(unanswered::isCancelled);
        Approvals empty = new Approvals(ApprovalHandler.DENY_ALL, Optional.of(_ -> CompletableFuture.completedStage(ImmutableMap.of())), Duration.ofMinutes(1),
                checkout, Runnable::run);
        assertThat(decide(empty, question()).decision()).isEqualTo(ProviderApprovalDecision.Decision.DENY);
    }

    @Test
    void onlyASessionWithAQuestionHandlerMayAsk()
    {
        agent.startSession(SessionOptions.in(checkout).build());
        assertThat(driver.last().context.asksUser()).isFalse();
        agent.startSession(SessionOptions.in(checkout).questions(_ -> new CompletableFuture<>()).build());
        assertThat(driver.last().context.asksUser()).isTrue();
    }

    private static ProviderApprovalRequest question()
    {
        String detail = """
                {"questions":[
                  {"question":"Which fruit?","header":"Fruit","multiSelect":false,"options":[{"label":"Apple","description":"Red"},{"label":"Banana","description":"Yellow"}]},
                  {"question":"Which colors?","header":"Colors","multiSelect":true,"options":[{"label":"Red"},{"label":"Blue"}]}]}
                """;
        return new ProviderApprovalRequest(ProviderApprovalRequest.Kind.TOOL, "Allow Codex AskUserQuestion", detail, ImmutableList.of(ProviderApprovalRequest.QUESTION_TOOL),
                ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), false, ProviderAgentEventSource.root(), Instant.now());
    }

    private static ProviderApprovalRequest command(String command)
    {
        return new ProviderApprovalRequest(ProviderApprovalRequest.Kind.COMMAND, "Allow command", command, ImmutableList.of(command),
                ImmutableList.of(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY), Optional.empty(), false,
                ProviderAgentEventSource.root(), Instant.now(), Optional.of(new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS,
                        ImmutableList.of("github.com"), Optional.empty())), Optional.of(command));
    }

    private static ProviderApprovalDecision decide(Approvals approvals, ProviderApprovalRequest request)
            throws Exception
    {
        return approvals.request(request, Duration.ofMinutes(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private Optional<BigDecimal> turnCost(Session session, int turns, String conversationTotal)
            throws Exception
    {
        Turn turn = session.start(Input.text("go"), _ -> {});
        FakeDriver.FakeSession driverSession = driver.last();
        eventually(() -> driverSession.turns.size() == turns);
        emit(new ProviderAgentEvent.Usage(new ProviderUsageSample(Optional.empty(), ProviderUsageSample.Semantics.FINAL_WITH_SESSION_COST, 10, 0, 5,
                Optional.of(new BigDecimal(conversationTotal)))));
        driverSession.turn().complete("done");
        return ((TurnResult.Completed) turn.await()).usage().cost();
    }

    private void emit(ProviderAgentEvent event)
    {
        driver.last().events.emit(event);
    }

    private static void complete(Turn turn, FakeDriver.FakeSession session, int turns)
            throws Exception
    {
        eventually(() -> session.turns.size() == turns);
        session.turn().complete("done");
        assertThat(turn.result().toCompletableFuture().get(5, TimeUnit.SECONDS)).isInstanceOf(TurnResult.Completed.class);
    }

    private static FakeDriver.FakeSession.FakeTurn running(FakeDriver.FakeSession session)
            throws InterruptedException
    {
        eventually(() -> !session.turns.isEmpty());
        return session.turn();
    }

    private static void eventually(BooleanSupplier condition)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition within 5 seconds").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static void sleepBriefly()
    {
        try {
            Thread.sleep(5);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void sessionOptionsTurnOptionsReachTheProvider()
            throws Exception
    {
        driver.models = ImmutableList.of(new ProviderModel("main", "Main", "", ImmutableList.of(), Optional.empty(), true, ImmutableList.of(), ImmutableList.of()));
        Session session = agent.startSession(SessionOptions.in(checkout).model("main").build());
        session.start(Input.text("go"), TurnOptions.defaults().withTimeLimit(Duration.ofMinutes(2)).withServiceTier("priority"), _ -> {});
        FakeDriver.FakeSession.FakeTurn running = running(driver.last());
        assertThat(running.command.maximumRuntime()).isEqualTo(Duration.ofMinutes(2));
        assertThat(running.command.serviceTier()).contains("priority");
        assertThat(running.command.model()).contains("main");
    }
}

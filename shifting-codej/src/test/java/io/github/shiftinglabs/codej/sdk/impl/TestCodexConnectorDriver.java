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
import io.github.shiftinglabs.codej.cli.driver.AgentCapabilities;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentProviderStatus;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.RunningTurn;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.mcp.PermissionBroker;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestConnectorDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexConnectorDriver
        extends AbstractTestConnectorDriver
{
    @TempDir
    private Path directory;

    @Test
    void forwardsDriverSessionAndTurnBehavior()
    {
        FakeDriver delegate = new FakeDriver();
        delegate.models = List.of(new ProviderModel("custom", "Custom", "", List.of(), Optional.empty(), true, List.of("text"), List.of()));
        delegate.savesOnAnswer = true;
        AgentSessionContext context = context(Optional.empty());
        AgentEventSink events = event -> {};
        try (CodexConnectorDriver driver = driver(delegate)) {
            assertThat(driver.identity()).isEqualTo(new ConnectorIdentity("codex", "profile"));
            assertThat(driver.id()).isEqualTo(delegate.id());
            assertThat(driver.label()).isEqualTo(delegate.label());
            assertThat(driver.capabilities()).isSameAs(delegate.capabilities());
            assertThat(driver.probe()).isSameAs(delegate.status);
            assertThat(driver.probe(Optional.of("custom"))).isSameAs(delegate.status);
            assertThat(delegate.probes).hasValue(2);
            assertThat(driver.models()).isSameAs(delegate.models);
            assertThat(driver.quota()).isSameAs(delegate.quota);
            assertThat(driver.reviewChildAgents()).isEqualTo(delegate.reviewChildAgents());
            assertThat(driver.reviewChildAgents(context.sessionReference(), context.model())).isFalse();

            AgentSession session = driver.open(context, events);
            assertThat(delegate.last().context).isSameAs(context);
            assertThat(delegate.last().events).isSameAs(events);
            assertThat(session.reference()).isEqualTo(delegate.last().reference());
            assertThat(session.resumable()).isFalse();
            assertThat(session.retained()).isFalse();
            delegate.last().parked = true;
            delegate.last().answered = true;
            assertThat(session.resumable()).isTrue();
            assertThat(session.retained()).isTrue();
            assertThat(session.prepareTurn(context, events)).isTrue();
            AgentTurnCommand command = new AgentTurnCommand("turn", "Hello", List.of(), Duration.ZERO);
            assertThat(session.start(command)).isSameAs(delegate.last().turn());
            assertThat(delegate.last().turn().command).isSameAs(command);
            session.allowReuse();
            session.preventReuse();
            session.close();
            session.close();
            assertThat(delegate.last().closed).isTrue();
            assertThat(session.prepareTurn(context, events)).isFalse();
            assertThatThrownBy(() -> session.start(command)).isInstanceOf(AgentUnavailableException.class).hasMessage("Codex session is closed");
        }
    }

    @Test
    void validatesReferencesWithoutOpeningSessionsOrProbing()
    {
        FakeDriver delegate = new FakeDriver();
        try (CodexConnectorDriver driver = driver(delegate)) {
            for (int version : List.of(1, 3)) {
                driver.validate(context(Optional.of(new AgentSessionRef(delegate.id(), version, "saved"))));
            }
            for (AgentSessionRef reference : List.of(new AgentSessionRef(AgentLaneId.CLAUDE_CLI, 3, "other-lane"),
                    new AgentSessionRef(delegate.id(), 4, "future-contract"))) {
                AgentSessionContext context = context(Optional.of(reference));
                assertThatThrownBy(() -> driver.validate(context)).isInstanceOf(AgentUnavailableException.class).hasMessage("Unsupported Codex session reference");
                assertThatThrownBy(() -> driver.open(context, event -> {})).isInstanceOf(AgentUnavailableException.class)
                        .hasMessage("Unsupported Codex session reference");
            }
            AgentSessionContext obsolete = context(Optional.of(new AgentSessionRef(delegate.id(), 2, "obsolete")));
            assertThatThrownBy(() -> driver.open(obsolete, event -> {})).isInstanceOf(AgentUnavailableException.class)
                    .hasMessage("This Codex conversation has an outdated tool list. Start a new task to use live tool discovery");
            assertThat(delegate.sessions).isEmpty();
            assertThat(delegate.probes).hasValue(0);
            assertThat(directory.resolve("provider")).doesNotExist();
        }
    }

    @Test
    void keepsExistingScopesAndModelsValidWithoutSideEffects()
    {
        FakeDriver delegate = new FakeDriver();
        try (CodexConnectorDriver driver = driver(delegate)) {
            for (AgentPermissionPolicy policy : AgentPermissionPolicy.values()) {
                for (SessionAccess access : SessionAccess.values()) {
                    AgentPermissionScope scope = new AgentPermissionScope(policy, access, false);
                    driver.validate(context(Optional.empty()).withTaskScope(scope, false));
                }
            }
            assertThat(delegate.sessions).isEmpty();
            assertThat(delegate.probes).hasValue(0);
            assertThat(directory.resolve("provider")).doesNotExist();
        }
    }

    @Test
    void checksExistingLaunchConstraintsBeforeDelegateSideEffects()
    {
        FakeDriver delegate = new FakeDriver();
        try (CodexConnectorDriver driver = driver(delegate)) {
            for (SessionAccess access : List.of(SessionAccess.TEXT_ONLY, SessionAccess.TOOLS_ONLY)) {
                AgentPermissionScope scope = new AgentPermissionScope(AgentPermissionPolicy.AUTO, access, false);
                assertInvalidScope(driver, scope.withNetwork(true), "A text-only Codex profile has no network");
                assertInvalidScope(driver, scope.withWebSearch(true), "A text-only Codex profile has no web search");
                assertInvalidScope(driver, scope.withReadOnlySubagents(true), "A text-only or fixed Codex profile cannot gate read-only subagents");
            }
            AgentPermissionScope fixed = new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false)
                    .withFixedSandbox(true).withReadOnlySubagents(true);
            assertInvalidScope(driver, fixed, "A text-only or fixed Codex profile cannot gate read-only subagents");
            assertThat(delegate.sessions).isEmpty();
            assertThat(delegate.probes).hasValue(0);
        }
    }

    @Test
    void closesAllLiveSessionsAndKeepsSavedHistory()
            throws Exception
    {
        Path transcript = Files.writeString(directory.resolve("rollout.jsonl"), "Saved conversation");
        FakeDriver delegate = new FakeDriver();
        CodexConnectorDriver driver = driver(delegate);
        AgentSession first = driver.open(context(Optional.empty()), event -> {});
        driver.open(context(Optional.empty()), event -> {});
        first.close();
        driver.close();
        driver.close();
        assertThat(delegate.sessions).allSatisfy(session -> assertThat(session.closed).isTrue());
        assertThat(transcript).hasContent("Saved conversation");
        assertThatThrownBy(() -> driver.validate(context(Optional.empty()))).isInstanceOf(AgentUnavailableException.class)
                .hasMessage("Codex connector is closed");
        assertThatThrownBy(() -> driver.open(context(Optional.empty()), event -> {})).isInstanceOf(AgentUnavailableException.class)
                .hasMessage("Codex connector is closed");
    }

    @Test
    void reportsUnverifiedShutdownAndRetriesIt()
    {
        FakeDriver delegate = new FakeDriver();
        CodexConnectorDriver driver = driver(delegate);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        delegate.stopFails = true;
        assertThatThrownBy(driver::close).isInstanceOf(IllegalStateException.class).hasMessage("The process is still running");
        assertThat(delegate.last().closed).isFalse();
        assertThatThrownBy(() -> session.start(new AgentTurnCommand("turn", "Hello", List.of(), Duration.ZERO)))
                .isInstanceOf(AgentUnavailableException.class).hasMessage("Codex connector is closed");
        delegate.stopFails = false;
        driver.close();
        driver.close();
        assertThat(delegate.last().closed).isTrue();
    }

    @Test
    void preservesOpeningFailureWithoutRetainingAPhantomSession()
    {
        FakeDriver delegate = new FakeDriver();
        RuntimeException failure = new AgentUnavailableException("Provider could not open");
        delegate.openFailure = Optional.of(failure);
        try (CodexConnectorDriver driver = driver(delegate)) {
            assertThatThrownBy(() -> driver.open(context(Optional.empty()), event -> {})).isSameAs(failure);
            assertThat(delegate.sessions).isEmpty();
        }
    }

    @Test
    void closesASessionThatFinishesOpeningAfterClose()
            throws Exception
    {
        FakeDriver delegate = new FakeDriver();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch finishOpening = new CountDownLatch(1);
        CodexConnectorDriver driver = driver(openingDriver(() -> {
            opening.countDown();
            await(finishOpening);
            return delegate.open(context(Optional.empty()), event -> {});
        }));
        CompletableFuture<AgentSession> pending = CompletableFuture.supplyAsync(() -> driver.open(context(Optional.empty()), event -> {}));
        try {
            assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class).hasMessage("Codex provider operations are still in progress");
        }
        finally {
            finishOpening.countDown();
        }
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AgentUnavailableException.class);
        assertThat(delegate.last().closed).isTrue();
        driver.close();
    }

    @Test
    void reportsProviderWorkStillRunningDuringClose()
            throws Exception
    {
        CountDownLatch readingModels = new CountDownLatch(1);
        CountDownLatch finishReading = new CountDownLatch(1);
        AgentDriver provider = new AgentDriver()
        {
            @Override
            public AgentLaneId id() { return AgentLaneId.CODEX_CLI; }

            @Override
            public AgentCapabilities capabilities() { return AgentCapabilities.none(); }

            @Override
            public AgentProviderStatus probe() { throw new AssertionError("Unexpected probe"); }

            @Override
            public List<ProviderModel> models()
            {
                readingModels.countDown();
                await(finishReading);
                return List.of();
            }

            @Override
            public AgentSession open(AgentSessionContext context, AgentEventSink events) { throw new AssertionError("Unexpected session"); }
        };
        CodexConnectorDriver driver = driver(provider);
        CompletableFuture<List<ProviderModel>> pending = CompletableFuture.supplyAsync(driver::models);
        try {
            assertThat(readingModels.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(driver::close).isInstanceOf(AgentUnavailableException.class).hasMessage("Codex provider operations are still in progress");
        }
        finally {
            finishReading.countDown();
        }
        assertThat(pending.get(5, TimeUnit.SECONDS)).isEmpty();
        driver.close();
        assertThatThrownBy(driver::models).isInstanceOf(AgentUnavailableException.class).hasMessage("Codex connector is closed");
    }

    @Test
    void providerCallbacksCannotDeadlockWithConnectorClose()
            throws Exception
    {
        Object providerLock = new Object();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        AgentSession providerSession = new AgentSession()
        {
            @Override
            public AgentSessionRef reference() { return new AgentSessionRef(AgentLaneId.CODEX_CLI, 3, "saved"); }

            @Override
            public RunningTurn start(AgentTurnCommand command) { throw new AssertionError("Unexpected turn"); }

            @Override
            public void close()
            {
                closeEntered.countDown();
                synchronized (providerLock) {
                    // A provider callback holds this lock, as Codex does while delivering events.
                }
            }
        };
        CodexConnectorDriver driver = driver(openingDriver(() -> providerSession));
        driver.open(context(Optional.empty()), event -> {});
        // Both actors must be runnable even when the host limits the common pool to one worker.
        ExecutorService actors = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> callback = CompletableFuture.runAsync(() -> {
                synchronized (providerLock) {
                    callbackEntered.countDown();
                    await(closeEntered);
                    assertThatThrownBy(() -> driver.validate(context(Optional.empty()))).isInstanceOf(AgentUnavailableException.class)
                            .hasMessage("Codex connector is closed");
                }
            }, actors);
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> closing = CompletableFuture.runAsync(driver::close, actors);
            callback.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            driver.close();
        }
        finally {
            closeEntered.countDown();
            actors.shutdownNow();
            assertThat(actors.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertInvalidScope(CodexConnectorDriver driver, AgentPermissionScope scope, String message)
    {
        AgentSessionContext context = context(Optional.empty()).withTaskScope(scope, false);
        assertThatThrownBy(() -> driver.validate(context)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> driver.open(context, event -> {})).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Override
    protected CodexConnectorDriver connector(AgentDriver delegate)
    {
        return driver(delegate);
    }

    private CodexConnectorDriver driver(AgentDriver delegate)
    {
        return new CodexConnectorDriver(delegate, new PermissionBroker(new ObjectMapper()), new ConnectorIdentity("codex", "profile"),
                Optional.empty(), Optional.empty());
    }

    private AgentDriver openingDriver(Supplier<AgentSession> session)
    {
        return new AgentDriver()
        {
            @Override
            public AgentLaneId id() { return AgentLaneId.CODEX_CLI; }

            @Override
            public AgentCapabilities capabilities() { return AgentCapabilities.none(); }

            @Override
            public AgentProviderStatus probe() { throw new AssertionError("Unexpected probe"); }

            @Override
            public AgentSession open(AgentSessionContext context, AgentEventSink events) { return session.get(); }
        };
    }

    private static void await(CountDownLatch latch)
    {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CompletionException(interrupted);
        }
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference)
    {
        return new AgentSessionContext("task", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO, reference,
                (request, timeout) -> { throw new AssertionError("Unexpected approval"); }, Optional.of("custom"), Optional.of("high"), SessionAccess.FULL, "Instructions");
    }
}

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

import io.github.shiftinglabs.codej.AgentException;
import io.github.shiftinglabs.codej.cli.driver.AgentDriver;
import io.github.shiftinglabs.codej.cli.driver.AgentEventSink;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderModel;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.sdk.testing.AbstractTestConnectorDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeConnectorDriver
        extends AbstractTestConnectorDriver
{
    private static final ConnectorIdentity IDENTITY = new ConnectorIdentity("claude-code", "profile");

    @TempDir
    private Path directory;

    @Test
    void delegatesDriverBehaviorAndPreservesTheSessionContext()
    {
        FakeDriver delegate = new FakeDriver();
        delegate.models = List.of(new ProviderModel("custom", "Custom model", "", List.of(), Optional.empty(), true, List.of("text"), List.of()));
        ClaudeConnectorDriver driver = new ClaudeConnectorDriver(delegate, IDENTITY, List.of());
        AgentSessionContext context = context(Optional.of(new AgentSessionRef(delegate.id(), 1, "saved-session")));
        AgentEventSink events = event -> {};

        assertThat(driver.identity()).isEqualTo(IDENTITY);
        assertThat(driver.id()).isEqualTo(delegate.id());
        assertThat(driver.label()).isEqualTo(delegate.label());
        assertThat(driver.capabilities()).isSameAs(delegate.status.capabilities());
        assertThat(driver.probe()).isSameAs(delegate.status);
        assertThat(driver.probe(Optional.of("custom"))).isSameAs(delegate.status);
        assertThat(delegate.probes).hasValue(2);
        assertThat(driver.models()).isSameAs(delegate.models);
        assertThat(driver.quota()).isSameAs(delegate.quota);
        assertThat(driver.reviewChildAgents()).isEqualTo(delegate.reviewChildAgents());
        assertThat(driver.reviewChildAgents(context.sessionReference(), context.model())).isEqualTo(delegate.reviewChildAgents(context.sessionReference(), context.model()));
        assertThat(driver.open(context, events)).isSameAs(delegate.last());
        assertThat(delegate.last().context).isSameAs(context);
        assertThat(delegate.last().events).isSameAs(events);
    }

    @Test
    void rejectsOnlyIncompatibleReferencesBeforeOpeningTheDelegate()
    {
        FakeDriver delegate = new FakeDriver();
        ClaudeConnectorDriver driver = new ClaudeConnectorDriver(delegate, IDENTITY, List.of());
        for (AgentSessionRef reference : List.of(new AgentSessionRef(AgentLaneId.CLAUDE_CLI, 1, "other-lane"),
                new AgentSessionRef(delegate.id(), 2, "other-contract"))) {
            AgentSessionContext context = context(Optional.of(reference));
            assertThatThrownBy(() -> driver.validate(context)).isInstanceOf(AgentUnavailableException.class).hasMessage("Unsupported Claude session reference");
            assertThatThrownBy(() -> driver.open(context, event -> {})).isInstanceOf(AgentUnavailableException.class).hasMessage("Unsupported Claude session reference");
        }
        assertThat(delegate.sessions).isEmpty();
        assertThat(directory.resolve("provider")).doesNotExist();
    }

    @Test
    void keepsExistingModelsAndPermissionScopesValidWithoutSideEffects()
    {
        FakeDriver delegate = new FakeDriver();
        ClaudeConnectorDriver driver = new ClaudeConnectorDriver(delegate, IDENTITY, List.of());
        AgentSessionContext context = context(Optional.empty());
        for (AgentPermissionPolicy policy : AgentPermissionPolicy.values()) {
            for (SessionAccess access : SessionAccess.values()) {
                AgentPermissionScope scope = new AgentPermissionScope(policy, access, false).withNetwork(true)
                        .withUnsandboxedCommands(List.of("mvn *")).withUnixSockets(List.of("/var/run/docker.sock"))
                        .withReviewer(List.of("Builds download dependencies"), List.of("Never push"), true);
                driver.validate(context.withTaskScope(scope, false));
            }
        }
        assertThat(delegate.sessions).isEmpty();
        assertThat(delegate.probes).hasValue(0);
        assertThat(directory.resolve("provider")).doesNotExist();
    }

    @Test
    void closesOwnedResourcesInOrderOnlyOnceAndRejectsFurtherSessions()
    {
        FakeDriver delegate = new FakeDriver();
        List<String> closed = new ArrayList<>();
        ClaudeConnectorDriver driver = new ClaudeConnectorDriver(delegate, IDENTITY,
                List.of(() -> closed.add("driver"), () -> closed.add("broker"), () -> closed.add("sweep")));

        driver.close();
        driver.close();
        assertThat(closed).containsExactly("driver", "broker", "sweep");
        assertThatThrownBy(() -> driver.validate(context(Optional.empty()))).isInstanceOf(AgentUnavailableException.class).hasMessage("Claude driver is closed");
        assertThatThrownBy(() -> driver.open(context(Optional.empty()), event -> {})).isInstanceOf(AgentUnavailableException.class).hasMessage("Claude driver is closed");
        assertThat(delegate.sessions).isEmpty();
    }

    @Test
    void reportsShutdownFailureClosesLaterResourcesAndRetriesTheDriver()
    {
        List<String> closed = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        ClaudeConnectorDriver driver = new ClaudeConnectorDriver(new FakeDriver(), IDENTITY, List.of(() -> {
            closed.add("driver");
            if (attempts.getAndIncrement() == 0) {
                throw new AgentUnavailableException("process tree is not verified stopped");
            }
        }, () -> closed.add("broker"), () -> closed.add("sweep")));

        assertThatThrownBy(driver::close).isInstanceOf(AgentException.class).hasMessage("process tree is not verified stopped");
        assertThat(closed).containsExactly("driver", "broker", "sweep");
        driver.close();
        driver.close();
        assertThat(closed).containsExactly("driver", "broker", "sweep", "driver", "broker", "sweep");
        assertThat(attempts).hasValue(2);
    }

    @Override
    protected ClaudeConnectorDriver connector(AgentDriver delegate)
    {
        return new ClaudeConnectorDriver(delegate, IDENTITY, List.of());
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference)
    {
        return new AgentSessionContext("task", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO, reference,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), Optional.of("custom-model"), Optional.of("custom-effort"),
                SessionAccess.FULL, "Managed instructions");
    }
}

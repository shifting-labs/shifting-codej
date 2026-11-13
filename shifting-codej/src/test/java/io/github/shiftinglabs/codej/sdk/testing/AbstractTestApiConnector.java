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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentSession;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionRef;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnCommand;
import io.github.shiftinglabs.codej.cli.driver.AgentTurnResult;
import io.github.shiftinglabs.codej.cli.driver.AgentUnavailableException;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared direct-API contracts. Bind providers through controlled fakes, never live inference or native processes. */
public abstract class AbstractTestApiConnector<C>
        extends AbstractTestConnector<C>
{
    @Override
    protected AgentSessionContext contractSessionContext() { return context(Optional.empty()); }

    private AgentSessionContext context(Optional<AgentSessionRef> reference)
    {
        return new AgentSessionContext("api-contract", contractDirectory(), contractDirectory().resolve("runtime"),
                contractDirectory().resolve("runtime/provider"), AgentPermissionPolicy.AUTO, reference,
                (request, timeout) -> { throw new AssertionError("Text sessions request no approvals"); },
                Optional.empty(), Optional.empty(), SessionAccess.TEXT_ONLY, "");
    }

    @Test
    protected void servesTextThroughExistingSessionTurnAndEventContracts()
            throws Exception
    {
        List<ProviderAgentEvent> events = new ArrayList<>();
        try (ConnectorDriver driver = connector().create(configuration(), contractContext());
                AgentSession session = driver.open(contractSessionContext(), events::add)) {
            assertThat(session.resumable()).isTrue();
            assertThat(session.retained()).isFalse();
            var turn = session.start(new AgentTurnCommand("api-contract", "Hello", List.of(), Duration.ZERO));
            assertThat(turn.reference().value()).isEqualTo("api-contract");
            assertThat(turn.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
            assertThat(events).anyMatch(event -> event instanceof ProviderAgentEvent.Message message
                    && message.phase() == ProviderAgentEvent.MessagePhase.FINAL);
            assertThat(turn.setup()).isEmpty();
            turn.cancel();
        }
    }

    @Test
    protected void resumesDurableReplayAfterTheOwningDriverClosed()
            throws Exception
    {
        AgentSessionRef reference;
        try (ConnectorDriver driver = connector().create(configuration(), contractContext());
                AgentSession session = driver.open(contractSessionContext(), event -> {})) {
            session.start(new AgentTurnCommand("first", "Hello", List.of(), Duration.ZERO)).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            reference = session.reference();
        }
        try (ConnectorDriver driver = connector().create(configuration(), contractContext());
                AgentSession session = driver.open(context(Optional.of(reference)), event -> {})) {
            assertThat(session.reference()).isEqualTo(reference);
            assertThat(session.start(new AgentTurnCommand("next", "Continue", List.of(), Duration.ZERO)).completion()
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        }
    }

    @Test
    protected void refusesFilesystemConstraintsBeforeOpeningAConversation()
    {
        AgentSessionContext supported = contractSessionContext();
        AgentSessionContext unsupported = new AgentSessionContext(supported.taskId(), supported.worktree(), supported.runtimeDirectory(), supported.providerDirectory(),
                AgentPermissionPolicy.AUTO, Optional.empty(), supported.approvals(), Optional.empty(), Optional.empty(), SessionAccess.READ_ONLY, "");
        try (ConnectorDriver driver = connector().create(configuration(), contractContext())) {
            assertThatThrownBy(() -> driver.open(unsupported, event -> {})).isInstanceOf(AgentUnavailableException.class);
            assertThat(contractContext().profileDirectory()).doesNotExist();
        }
    }
}

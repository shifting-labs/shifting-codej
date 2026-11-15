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
package io.github.shiftinglabs.codej.tool.testing;

import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Baseline behavior for alternate implementations of a logical operation, with controlled backend effects. */
public abstract class AbstractTestAgentTool<P, R>
{
    protected abstract AgentTool<P, R> tool();

    protected abstract P input();

    protected abstract R expected();

    protected abstract ToolRequirements requirements();

    protected abstract int calls();

    protected abstract void failBackend();

    protected abstract ToolFailure expectedFailure();

    protected boolean preparesExternalResource() { return false; }

    private ToolContractFixtures.Context context()
    {
        var context = new ToolContractFixtures.Context();
        context.grants = requirements();
        return context;
    }

    @Test
    void requirementsDoNotExecuteEffectsAndExecutionUsesTheTypedResultContract()
            throws Exception
    {
        AgentTool<P, R> tool = tool();
        var context = context();
        int before = calls();
        assertThat(tool.requirements(input(), context).capabilities()).isEqualTo(requirements().capabilities());
        assertThat(calls()).isEqualTo(before);
        assertThat(tool.definition()).isSameAs(tool.definition());
        ToolExecution<R> execution = tool.execute(input(), context);
        ToolOutcome<R> result = execution.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(ToolOutcome.State.COMPLETED);
        assertThat(result.value()).contains(expected());
        assertThat(result.failure()).isEmpty();
        assertThat(tool.definition().results().encode(result.value().orElseThrow()).text()).isNotBlank();
        assertThat(calls()).isEqualTo(before + 1);
        if (preparesExternalResource()) {
            assertThat(context.prepared).isNotEmpty();
        }
        assertThat(execution.stopAndVerify().toCompletableFuture().get(2, TimeUnit.SECONDS).stopped()).isTrue();
    }

    @Test
    void successfulCompletionSurvivesCancellationAndObserverMutation()
            throws Exception
    {
        var execution = tool().execute(input(), context());
        execution.cancel();
        execution.cancel();
        execution.completion().toCompletableFuture().obtrudeValue(ToolOutcome.uncertain(expectedFailure()));
        assertThat(execution.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).value()).contains(expected());
        assertThat(execution.stopAndVerify().toCompletableFuture().get(2, TimeUnit.SECONDS).stopped()).isTrue();
        assertThat(execution.stopAndVerify().toCompletableFuture().get(2, TimeUnit.SECONDS).stopped()).isTrue();
    }

    @Test
    void deniedAuthorityAndFailedDurableIntentPreventEffects()
    {
        var denied = context();
        denied.grants = ToolRequirements.none();
        if (!requirements().capabilities().isEmpty()) {
            assertThatThrownBy(() -> tool().execute(input(), denied)).isInstanceOf(SecurityException.class);
            assertThat(calls()).isZero();
            assertThat(denied.prepared).isEmpty();
        }
        if (preparesExternalResource()) {
            var unavailable = context();
            unavailable.failJournal = true;
            assertThatThrownBy(() -> tool().execute(input(), unavailable)).isInstanceOf(IllegalStateException.class);
            assertThat(calls()).isZero();
        }
    }

    @Test
    void cancelledAndExpiredInvocationsCannotStartEffects()
            throws Exception
    {
        var cancelled = context();
        cancelled.cancelled = true;
        beforeEffects(cancelled);
        var expired = context();
        expired.deadline = Optional.of(Instant.EPOCH);
        beforeEffects(expired);
        assertThat(calls()).isZero();
    }

    private void beforeEffects(ToolContractFixtures.Context context)
            throws Exception
    {
        try {
            var outcome = tool().execute(input(), context).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(outcome.state()).isEqualTo(ToolOutcome.State.CANCELLED);
        }
        catch (CancellationException expected) {
            // Synchronous rejection before effects and an explicit cancelled outcome are both valid.
        }
        assertThat(context.prepared).isEmpty();
    }

    @Test
    void backendFailuresAreTypedAndDoNotExposeRawDiagnostics()
            throws Exception
    {
        failBackend();
        var execution = tool().execute(input(), context());
        var result = execution.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(result.state()).isEqualTo(ToolOutcome.State.FAILED);
        assertThat(result.value()).isEmpty();
        assertThat(result.failure()).contains(expectedFailure());
        assertThat(result.failure().orElseThrow().message()).doesNotContain("private backend diagnostic");
        assertThat(execution.stopAndVerify().toCompletableFuture().get(2, TimeUnit.SECONDS).stopped()).isTrue();
    }
}

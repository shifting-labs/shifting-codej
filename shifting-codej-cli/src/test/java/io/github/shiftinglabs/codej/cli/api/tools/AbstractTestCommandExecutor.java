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
package io.github.shiftinglabs.codej.cli.api.tools;

import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared contained-command lifecycle; implementations add their native containment probes separately. */
public abstract class AbstractTestCommandExecutor
{
    public interface Fixture
            extends AutoCloseable
    {
        CommandExecutor executor();

        ResourceTestContext context();

        int started();

        List<String> acknowledged();

        @Override
        void close()
                throws Exception;
    }

    protected abstract Fixture open()
            throws Exception;

    static ToolRequirements grants() { return ToolRequirements.builder().read(Path.of(".")).require(ToolCapability.PROCESS_EXECUTE).build(); }

    static ToolMessages.ShellCommand request() { return new ToolMessages.ShellCommand(List.of("/usr/bin/true"), ".", 1); }

    @Test
    void launchIntentPrecedesEffectsAndAcknowledgementFollowsTheDurableResult()
            throws Exception
    {
        try (var fixture = open()) {
            var context = fixture.context();
            var execution = fixture.executor().start(request(), context);
            var observed = execution.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(observed.state()).isEqualTo(ToolOutcome.State.COMPLETED);
            assertThat(observed.value().orElseThrow().exitCode()).isZero();
            assertThat(context.trace).containsExactly("owner-prepared", "invocation-prepared", "start");
            assertThat(context.prepared).hasSize(1);
            assertThat(context.prepared.getFirst().kind()).isEqualTo("process.launch");
            assertThat(fixture.acknowledged()).isEmpty();
            execution.completion().toCompletableFuture().obtrudeException(new IllegalStateException("observer only"));
            execution.cancel();
            assertThat(execution.completion().toCompletableFuture().get().value()).isEqualTo(observed.value());
            assertThat(execution.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
            assertThat(fixture.acknowledged()).isEmpty();
            context.trace.add("durable-result");
            execution.committed();
            execution.committed();
            assertThat(fixture.acknowledged()).hasSize(1);
        }
    }

    @Test
    void oldHandlesCannotCancelStopOrAcknowledgeALaterInvocation()
            throws Exception
    {
        try (var fixture = open()) {
            var first = fixture.executor().start(request(), fixture.context());
            first.completion().toCompletableFuture().get();
            first.committed();
            var second = fixture.executor().start(request(), fixture.context());
            second.completion().toCompletableFuture().get();
            first.cancel();
            first.stopAndVerify().toCompletableFuture().get();
            first.committed();
            assertThat(fixture.acknowledged()).hasSize(1);
            second.committed();
            assertThat(fixture.acknowledged()).hasSize(2);
            assertThat(fixture.started()).isEqualTo(2);
        }
    }

    @Test
    void permissionsCancellationAndDeadlinePreventLaunch()
            throws Exception
    {
        try (var fixture = open()) {
            for (var grants : List.of(ToolRequirements.none(), ToolRequirements.builder().require(ToolCapability.PROCESS_EXECUTE).build(),
                    ToolRequirements.builder().read(Path.of("subdirectory")).require(ToolCapability.PROCESS_EXECUTE).build())) {
                assertThatThrownBy(() -> fixture.executor().start(request(), new ResourceTestContext(grants))).isInstanceOf(SecurityException.class);
            }
            var cancelled = new ResourceTestContext(grants());
            cancelled.cancelled = true;
            var expired = new ResourceTestContext(grants());
            expired.deadline = Optional.of(Instant.EPOCH);
            for (var context : List.of(cancelled, expired)) {
                assertThatThrownBy(() -> fixture.executor().start(request(), context)).isInstanceOf(CancellationException.class);
            }
            assertThat(fixture.started()).isZero();
            assertThat(fixture.context().prepared).isEmpty();
        }
    }

    @Test
    void failedInvocationJournalCannotLaunchAndUnstartedReservationIsReleased()
            throws Exception
    {
        try (var fixture = open()) {
            fixture.context().failJournal = true;
            assertThatThrownBy(() -> fixture.executor().start(request(), fixture.context())).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.started()).isZero();
            assertThat(fixture.acknowledged()).hasSize(1);
            fixture.context().failJournal = false;
            var next = fixture.executor().start(request(), fixture.context());
            assertThat(next.completion().toCompletableFuture().get().state()).isEqualTo(ToolOutcome.State.COMPLETED);
            next.committed();
        }
    }
}

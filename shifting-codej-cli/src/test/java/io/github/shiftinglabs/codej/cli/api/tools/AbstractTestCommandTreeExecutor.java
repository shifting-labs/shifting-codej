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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bind a controlled backend to these lifecycle contracts; OS containment needs backend-specific tests too. */
public abstract class AbstractTestCommandTreeExecutor
{
    protected interface Fixture
            extends AutoCloseable
    {
        CommandExecutor executor();

        ResourceTestContext context();

        void finish();

        void verified(boolean value);

        int starts();

        int acknowledgements();

        @Override
        void close();
    }

    protected abstract Fixture open();

    @Test
    void preparedOwnershipPrecedesEffectsAndAcknowledgementFollowsVerifiedCompletion()
            throws Exception
    {
        try (var f = open()) {
            var handle = f.executor().startTree(AbstractTestCommandExecutor.request(), CommandLimits.defaults(), f.context());
            assertThat(f.context().trace).containsSubsequence("invocation-prepared", "start");
            assertThat(f.context().prepared).hasSize(1);
            assertThat(handle.completion().toCompletableFuture()).isNotDone();
            assertThat(f.acknowledgements()).isZero();
            assertThatThrownBy(handle::committed).isInstanceOf(IllegalStateException.class);
            f.finish();
            assertThat(handle.completion().toCompletableFuture().get().value().orElseThrow().exitCode()).isZero();
            assertThat(handle.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
            handle.committed();
            handle.committed();
            assertThat(f.acknowledgements()).isEqualTo(1);
        }
    }

    @Test
    void cancelledObserversCannotCancelTheOwnedCompletion()
            throws Exception
    {
        try (var f = open()) {
            var handle = f.executor().startTree(AbstractTestCommandExecutor.request(), CommandLimits.defaults(), f.context());
            handle.completion().toCompletableFuture().cancel(true);
            f.finish();
            assertThat(handle.completion().toCompletableFuture().get().value()).isPresent();
            handle.stopAndVerify().toCompletableFuture().get();
            handle.committed();
        }
    }

    @Test
    void unverifiedStopCannotAcknowledgeOrReleaseOwnership()
            throws Exception
    {
        try (var f = open()) {
            var handle = f.executor().startTree(AbstractTestCommandExecutor.request(), CommandLimits.defaults(), f.context());
            f.finish();
            f.verified(false);
            handle.cancel();
            assertThat(handle.stopAndVerify().toCompletableFuture().get().stopped()).isFalse();
            assertThatThrownBy(handle::committed).isInstanceOf(IllegalStateException.class);
            assertThat(f.acknowledgements()).isZero();
            f.verified(true);
            assertThat(handle.stopAndVerify().toCompletableFuture().get().stopped()).isTrue();
            handle.committed();
            assertThat(f.acknowledgements()).isEqualTo(1);
        }
    }

    @Test
    void rejectedJournalAndCancellationPreventBackendEffects()
    {
        try (var f = open()) {
            f.context().failJournal = true;
            assertThatThrownBy(() -> f.executor().startTree(AbstractTestCommandExecutor.request(), CommandLimits.defaults(), f.context()))
                    .isInstanceOf(IllegalStateException.class);
            f.context().failJournal = false;
            f.context().cancelled = true;
            assertThatThrownBy(() -> f.executor().startTree(AbstractTestCommandExecutor.request(), CommandLimits.defaults(), f.context()))
                    .isInstanceOf(CancellationException.class);
            assertThat(f.starts()).isZero();
        }
    }

    @Test
    void earlierHandlesCannotAffectLaterInvocations()
            throws Exception
    {
        try (var f = open()) {
            var first = f.executor().startTree(AbstractTestCommandExecutor.request(), CommandLimits.defaults(), f.context());
            f.finish();
            first.stopAndVerify().toCompletableFuture().get();
            first.committed();
            var second = f.executor().startTree(AbstractTestCommandExecutor.request(), CommandLimits.defaults(), f.context());
            first.cancel();
            first.stopAndVerify().toCompletableFuture().get();
            first.committed();
            assertThat(second.completion().toCompletableFuture()).isNotDone();
            assertThat(f.acknowledgements()).isEqualTo(1);
            f.finish();
            second.stopAndVerify().toCompletableFuture().get();
            second.committed();
            assertThat(f.acknowledgements()).isEqualTo(2);
        }
    }
}

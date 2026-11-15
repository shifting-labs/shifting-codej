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
package io.github.shiftinglabs.codej.cli.spi.tool;

import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolOutput;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import io.github.shiftinglabs.codej.tool.ToolResultCodec;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolExecution
{
    private static final ToolFailure FAILURE = new ToolFailure("FAILED", "safe diagnostic");

    @Test
    void completedHandlesAreQuiescentAndCannotBeMutatedByObservers()
            throws Exception
    {
        var execution = ToolExecutions.completed("private result");
        var observer = execution.completion().toCompletableFuture();
        observer.obtrudeException(new IllegalStateException("observer failure"));
        execution.cancel();
        execution.cancel();
        var actual = execution.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertThat(actual.state()).isEqualTo(ToolOutcome.State.COMPLETED);
        assertThat(actual.value()).contains("private result");
        assertThat(actual.failure()).isEmpty();
        assertThat(actual.toString()).doesNotContain("private result");
        var stopObserver = execution.stopAndVerify().toCompletableFuture();
        stopObserver.obtrudeValue(ToolStopResult.unverified("observer state"));
        assertThat(execution.stopAndVerify().toCompletableFuture().get(1, TimeUnit.SECONDS).stopped()).isTrue();
    }

    @Test
    void stoppedWorkCanHaveAnUncertainOutcomeAndAllFailureStatesAreExplicit()
            throws Exception
    {
        var failed = ToolOutcome.failed(FAILURE);
        var cancelled = ToolOutcome.cancelled(FAILURE);
        var uncertain = ToolOutcome.uncertain(FAILURE);
        assertThat(failed.state()).isEqualTo(ToolOutcome.State.FAILED);
        assertThat(cancelled.state()).isEqualTo(ToolOutcome.State.CANCELLED);
        assertThat(uncertain.state()).isEqualTo(ToolOutcome.State.UNCERTAIN);
        for (var outcome : java.util.List.of(failed, cancelled, uncertain)) {
            assertThat(outcome.value()).isEmpty();
            assertThat(outcome.failure()).contains(FAILURE);
        }
        var execution = ToolExecutions.finished(uncertain);
        assertThat(execution.stopAndVerify().toCompletableFuture().get(1, TimeUnit.SECONDS).stopped()).isTrue();
        assertThat(execution.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).state()).isEqualTo(ToolOutcome.State.UNCERTAIN);
        assertThatThrownBy(() -> ToolExecutions.finished(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolOutcome.completed(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolOutcome.failed(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void asynchronousBackendCanKeepOwnershipAfterCancelAndRetryStopVerification()
            throws Exception
    {
        var result = new CompletableFuture<ToolOutcome<String>>();
        var cancelled = new AtomicBoolean();
        var verified = new AtomicBoolean();
        ToolExecution<String> execution = new ToolExecution<>()
        {
            @Override
            public CompletionStage<ToolOutcome<String>> completion() { return result.minimalCompletionStage(); }

            @Override
            public void cancel() { cancelled.set(true); }

            @Override
            public CompletionStage<ToolStopResult> stopAndVerify()
            {
                return CompletableFuture.completedStage(verified.get() ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Backend still running"));
            }
        };
        execution.cancel();
        assertThat(cancelled).isTrue();
        assertThat(execution.completion().toCompletableFuture()).isNotDone();
        assertThat(execution.stopAndVerify().toCompletableFuture().get(1, TimeUnit.SECONDS).stopped()).isFalse();
        assertThat(execution.stopAndVerify().toCompletableFuture().get(1, TimeUnit.SECONDS).diagnostic()).contains("Backend still running");
        result.complete(ToolOutcome.completed("observed success"));
        verified.set(true);
        execution.cancel();
        assertThat(execution.completion().toCompletableFuture().get(1, TimeUnit.SECONDS).value()).contains("observed success");
        assertThat(execution.stopAndVerify().toCompletableFuture().get(1, TimeUnit.SECONDS).stopped()).isTrue();
    }

    @Test
    void extensionInterfacesRequireNeitherClassTokensNorRecordParameterTypes()
    {
        for (Class<?> contract : java.util.List.of(ToolArguments.class, ToolOutput.class, ToolParameters.class, ToolResultCodec.class,
                ToolDefinition.class, AgentTool.class, ToolExecution.class, ToolProvider.class, ToolSet.class,
                ToolPreparationContext.class, ToolExecutionContext.class, ToolSetContext.class)) {
            assertThat(contract.isInterface()).isTrue();
            for (var method : contract.getDeclaredMethods()) {
                assertThat(method.getGenericReturnType().getTypeName()).doesNotContain("java.lang.Class");
                for (var parameter : method.getGenericParameterTypes()) {
                    assertThat(parameter.getTypeName()).doesNotContain("java.lang.Class");
                }
            }
            for (var parameter : contract.getTypeParameters()) {
                for (var bound : parameter.getBounds()) {
                    assertThat(bound.getTypeName()).doesNotContain("java.lang.Record");
                }
            }
        }
    }

    @Test
    void malformedFailureStopAndOwnershipValuesAreRejected()
    {
        assertThatThrownBy(() -> new ToolFailure("bad code", "safe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolFailure("FAIL", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolStopResult(false, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolStopResult(true, Optional.of("unknown"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolStopResult.unverified(" ")).isInstanceOf(IllegalArgumentException.class);
        ToolResource resource = new ToolResource("supervised-command", "launch-123");
        assertThat(resource.kind()).isEqualTo("supervised-command");
        assertThat(resource.id()).isEqualTo("launch-123");
        assertThatThrownBy(() -> new ToolResource("bad kind", "id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolResource("kind", "prompt with secrets")).isInstanceOf(IllegalArgumentException.class);
    }
}

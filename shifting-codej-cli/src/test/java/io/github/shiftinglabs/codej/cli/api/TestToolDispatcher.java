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
package io.github.shiftinglabs.codej.cli.api;

import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.api.tools.LegacyToolAdapter;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecution;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutionContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolExecutions;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolOutcome;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolPreparationContext;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolStopResult;
import io.github.shiftinglabs.codej.tool.ToolDefinition;
import io.github.shiftinglabs.codej.tool.ToolDefinitions;
import io.github.shiftinglabs.codej.tool.ToolDescriptor;
import io.github.shiftinglabs.codej.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolDispatcher
{
    private static final ToolCapability CUSTOM = new ToolCapability("example.execute");
    private static final ToolRequirements REQUIREMENTS = ToolRequirements.builder().require(CUSTOM).build();
    private static final ToolDispatcher.Call CALL = new ToolDispatcher.Call("call", "example", "{\"count\":2}", "request:call");

    record Input(int count)
    {
        Input
        {
            if (count < 0) {
                throw new IllegalArgumentException("Negative count");
            }
        }
    }

    @Test
    void typedDispatchPreparesBeforeApprovalAndPersistsBeforeAcknowledging()
    {
        var context = new Context();
        AgentTool<Input, String> tool = tool(1, (input, execution) -> {
            assertThat(input).isEqualTo(new Input(2));
            assertThat(execution.invocationId()).isEqualTo("request:call");
            assertThat(execution.grants().capabilities()).containsExactly(CUSTOM);
            assertThat(execution.deadline()).isEqualTo(context.deadline());
            execution.recordPrepared(new ToolResource("example.resource", "owned-id"));
            execution.progress("Safe progress");
            context.trace.add("effect");
            return execution(ToolOutcome.completed("result"), context);
        });
        dispatcher(tool).turn(2).dispatch(CALL, context);
        assertThat(context.trace).containsExactly("approval", "started", "intent", "prepared", "progress", "effect", "stop", "result", "commit", "completed");
        assertThat(context.results).containsExactly(new ToolRegistration.Result("result", false));
        assertThat(context.interruptible).isFalse();
    }

    @Test
    void invalidParametersUnknownNamesAndDeniedApprovalsCannotStartEffects()
    {
        var context = new Context();
        ToolDispatcher dispatcher = dispatcher(tool(1, (input, execution) -> { throw new AssertionError("No effects"); }));
        var turn = dispatcher.turn(10);
        for (String invalid : List.of("{}", "{\"count\":-1}", "{\"count\":\"2\"}", "{\"count\":2,\"count\":3}", "{\"count\":2} {}")) {
            turn.dispatch(new ToolDispatcher.Call("call", "example", invalid, null), context);
        }
        turn.dispatch(new ToolDispatcher.Call("call", "unknown", "{}", null), context);
        assertThat(context.trace).containsOnly("result");
        assertThat(context.results).allMatch(ToolRegistration.Result::error);
        context.trace.clear();
        context.allow = false;
        turn.dispatch(CALL, context);
        assertThat(context.trace).containsExactly("approval", "result");
        assertThat(context.results.getLast().text()).isEqualTo("APPROVAL_DENIED:Tool invocation denied");
    }

    @Test
    void unknownCapabilitiesFailClosedBeforeApprovalAndIntentFailurePreventsEffects()
    {
        var context = new Context();
        ToolDispatcher dispatcher = dispatcher(tool(1, (input, execution) -> { throw new AssertionError("No effects"); }));
        context.grants = ToolRequirements.none();
        dispatcher.turn(1).dispatch(CALL, context);
        assertThat(context.trace).containsExactly("result");
        assertThat(context.results.getFirst().text()).startsWith("PERMISSION_DENIED:");
        context.grants = REQUIREMENTS;
        context.trace.clear();
        context.failIntent = true;
        assertThatThrownBy(() -> dispatcher.turn(1).dispatch(CALL, context)).isInstanceOf(IllegalStateException.class);
        assertThat(context.trace).containsExactly("approval", "started", "intent");
    }

    @Test
    void failedPreparedResourcePersistencePreventsItsExternalEffect()
    {
        var context = new Context();
        context.failPrepared = true;
        var dispatcher = dispatcher(tool(1, (input, execution) -> {
            execution.recordPrepared(new ToolResource("example.resource", "owned-id"));
            throw new AssertionError("No effects");
        }));
        assertThatThrownBy(() -> dispatcher.turn(1).dispatch(CALL, context)).isInstanceOf(ToolDispatcher.Uncertain.class);
        assertThat(context.trace).containsExactly("approval", "started", "intent", "prepared");
        assertThat(context.results).isEmpty();
    }

    @Test
    void unverifiedTerminationRetainsTheResultAndOwnershipUntilCloseCanVerify()
    {
        var context = new Context();
        context.stopped = false;
        ToolDispatcher dispatcher = dispatcher(tool(1, (input, execution) -> execution(ToolOutcome.completed("observed result"), context)));
        assertThatThrownBy(() -> dispatcher.turn(1).dispatch(CALL, context)).isInstanceOf(ToolDispatcher.Uncertain.class);
        assertThat(context.results).isEmpty();
        assertThat(context.trace).doesNotContain("commit", "completed");
        assertThatThrownBy(dispatcher::verifyStopped).isInstanceOf(ToolDispatcher.Uncertain.class);
        context.stopped = true;
        dispatcher.verifyStopped();
        assertThat(context.trace).doesNotContain("commit");
        int events = context.trace.size();
        dispatcher.verifyStopped();
        assertThat(context.trace).hasSize(events);
    }

    @Test
    void uncertainOutcomesNeverBecomeResultsEvenIfWorkIsStopped()
    {
        var context = new Context();
        var dispatcher = dispatcher(tool(1, (input, execution) -> execution(ToolOutcome.uncertain(new ToolFailure("UNKNOWN", "Effect uncertain")), context)));
        assertThatThrownBy(() -> dispatcher.turn(1).dispatch(CALL, context)).isInstanceOf(ToolDispatcher.Uncertain.class).hasMessage("Effect uncertain");
        assertThat(context.results).isEmpty();
        dispatcher.verifyStopped();
        assertThat(context.trace).doesNotContain("result", "commit");
    }

    @Test
    void persistenceFailureDoesNotAcknowledgeResourcesAndFailedOutcomesAreData()
    {
        var context = new Context();
        context.failResult = true;
        var dispatcher = dispatcher(tool(1, (input, execution) -> execution(ToolOutcome.completed("observed"), context)));
        assertThatThrownBy(() -> dispatcher.turn(1).dispatch(CALL, context)).isInstanceOf(IllegalStateException.class);
        assertThat(context.trace).doesNotContain("commit", "completed");
        dispatcher.verifyStopped();
        var failed = new Context();
        dispatcher(tool(1, (input, execution) -> execution(ToolOutcome.failed(new ToolFailure("BACKEND_FAILED", "Safe failure")), failed)))
                .turn(1).dispatch(CALL, failed);
        assertThat(failed.results).containsExactly(new ToolRegistration.Result("BACKEND_FAILED:Safe failure", true));
        assertThat(failed.trace).endsWith("result", "commit", "completed");
    }

    @Test
    void acknowledgementCanBeRetriedOnlyForDurablyRecordedResults()
    {
        var context = new Context();
        context.failCommit = true;
        var dispatcher = dispatcher(tool(1, (input, execution) -> execution(ToolOutcome.completed("observed"), context)));
        assertThatThrownBy(() -> dispatcher.turn(1).dispatch(CALL, context)).isInstanceOf(IllegalStateException.class);
        assertThat(context.results).containsExactly(new ToolRegistration.Result("observed", false));
        context.failCommit = false;
        dispatcher.committed();
        assertThat(context.trace).endsWith("result", "commit", "stop", "commit");
        int count = context.trace.size();
        dispatcher.committed();
        dispatcher.verifyStopped();
        assertThat(context.trace).hasSize(count);
        var uncommitted = new Context();
        uncommitted.failResult = true;
        var pending = dispatcher(tool(1, (input, execution) -> execution(ToolOutcome.completed("observed"), uncommitted)));
        assertThatThrownBy(() -> pending.turn(1).dispatch(CALL, uncommitted)).isInstanceOf(IllegalStateException.class);
        pending.committed();
        assertThat(uncommitted.trace).doesNotContain("commit");
        pending.verifyStopped();
    }

    @Test
    void resultEncodingFailureNeverErasesTheObservedInvocation()
    {
        var context = new Context();
        var normal = tool(1, (input, execution) -> execution(ToolOutcome.completed("result"), context));
        AgentTool<Input, String> badCodec = new AgentTool<>()
        {
            @Override
            public ToolDefinition<Input, String> definition()
            {
                return ToolDefinitions.of(normal.definition().descriptor(), normal.definition().parameters(), value -> {
                    throw new IllegalArgumentException("private diagnostic");
                });
            }

            @Override
            public ToolRequirements requirements(Input input, ToolPreparationContext preparation) { return REQUIREMENTS; }

            @Override
            public ToolExecution<String> execute(Input input, ToolExecutionContext execution) { return normal.execute(input, execution); }
        };
        dispatcher(badCodec).turn(1).dispatch(CALL, context);
        assertThat(context.results.getFirst().text()).isEqualTo("RESULT_ENCODING_FAILED:Tool ran but its result could not be encoded");
        assertThat(context.trace).endsWith("result", "commit", "completed");
    }

    @Test
    void legacyRegistrationsKeepExactWireDefinitionsAndAcceptPreMigrationPendingBatches()
    {
        Tool original = Tool.withSchema("example", "Example", " {} ", input -> "ok");
        var registration = ToolRegistration.create(LegacyToolAdapter.builder(original).build(), true, false, Optional.of(original), ToolFailure::message);
        var registry = ApiToolRegistry.registered(List.of(registration));
        var setup = ApiSessionStore.JSON.createObjectNode();
        setup.putArray("tools").addObject().put("name", original.name()).put("description", original.description()).put("schema", original.inputSchema());
        assertThat(registry.matches(setup)).isTrue();
        setup.set("toolContracts", ApiSessionStore.JSON.valueToTree(registry.contracts()));
        assertThat(registry.matches(setup)).isTrue();
        setup.withArray("toolContracts").removeAll().addObject().put("name", "example").put("version", 2).put("schemaFingerprint", registration.contract.schemaFingerprint());
        assertThat(registry.matches(setup)).isFalse();
        setup.remove("toolContracts");
        ((com.fasterxml.jackson.databind.node.ObjectNode) setup.withArray("tools").get(0)).put("schema", "{}");
        assertThat(registry.matches(setup)).isFalse();
        assertThatThrownBy(() -> ApiToolRegistry.registered(List.of(registration, registration))).isInstanceOf(IllegalArgumentException.class);
        var context = new Context();
        context.grants = ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build();
        new ToolDispatcher(registry, Duration.ofMillis(50)).turn(1).dispatch(new ToolDispatcher.Call("id", "example", "{}", null), context);
        assertThat(context.trace).doesNotContain("approval");
        assertThat(context.results).containsExactly(new ToolRegistration.Result("ok", false));
    }

    @Test
    void pendingMetadataMustMatchExactlyWithoutScalarCoercionOrIgnoredFields()
    {
        var registry = new ApiToolRegistry(List.of(Tool.withSchema("example", "42", "{}", input -> "ok")));
        var setup = ApiSessionStore.JSON.createObjectNode();
        var definition = setup.putArray("tools").addObject().put("name", "example").put("description", "42").put("schema", "{}");
        assertThat(registry.matches(setup)).isTrue();
        definition.put("description", 42);
        assertThat(registry.matches(setup)).isFalse();
        definition.put("description", "42").put("extra", "unreviewed");
        assertThat(registry.matches(setup)).isFalse();
        definition.remove("extra");
        setup.putNull("toolContracts");
        assertThat(registry.matches(setup)).isFalse();
        setup.set("toolContracts", ApiSessionStore.JSON.valueToTree(registry.contracts()));
        assertThat(registry.matches(setup)).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) setup.withArray("toolContracts").get(0)).put("version", "1");
        assertThat(registry.matches(setup)).isFalse();
    }

    @Test
    void typedPendingBatchesRequireVersionAndSchemaFingerprintAndCallsConsumeBudget()
    {
        var context = new Context();
        var tool = tool(1, (input, execution) -> ToolExecutions.completed("ok"));
        var registry = registry(tool);
        var setup = ApiSessionStore.JSON.createObjectNode();
        setup.putArray("tools").addObject().put("name", "example").put("description", "Example").put("schema", tool.definition().parameters().schema().json());
        assertThat(registry.matches(setup)).isFalse();
        setup.set("toolContracts", ApiSessionStore.JSON.valueToTree(registry.contracts()));
        assertThat(registry.matches(setup)).isTrue();
        assertThat(registry(tool(2, (input, execution) -> ToolExecutions.completed("ok"))).matches(setup)).isFalse();
        setup.put("toolContracts", "malformed");
        assertThat(registry.matches(setup)).isFalse();
        var turn = new ToolDispatcher(registry, Duration.ofMillis(50)).turn(1);
        turn.dispatch(CALL, context);
        assertThatThrownBy(() -> turn.dispatch(CALL, context)).isInstanceOf(ToolDispatcher.Limit.class);
        assertThat(context.results).hasSize(1);
    }

    private static ToolExecution<String> execution(ToolOutcome<String> outcome, Context context)
    {
        return new ToolExecution<>()
        {
            @Override
            public CompletionStage<ToolOutcome<String>> completion() { return CompletableFuture.completedStage(outcome); }

            @Override
            public void cancel() { context.trace.add("cancel"); }

            @Override
            public CompletionStage<ToolStopResult> stopAndVerify()
            {
                context.trace.add("stop");
                return CompletableFuture.completedStage(context.stopped ? ToolStopResult.verifiedStopped() : ToolStopResult.unverified("Still owned"));
            }

            @Override
            public void committed()
            {
                context.trace.add("commit");
                if (context.failCommit) {
                    throw new IllegalStateException("Acknowledgement unavailable");
                }
            }
        };
    }

    @FunctionalInterface
    interface Handler { ToolExecution<String> execute(Input input, ToolExecutionContext context); }

    private static AgentTool<Input, String> tool(int version, Handler handler)
    {
        ToolDefinition<Input, String> definition = ToolDefinitions.of(ToolDescriptor.builder("example", "Example").contractVersion(version).build(),
                RecordToolParameters.of(Input.class), ToolOutput::text);
        return new AgentTool<>()
        {
            @Override
            public ToolDefinition<Input, String> definition() { return definition; }

            @Override
            public ToolRequirements requirements(Input input, ToolPreparationContext preparation) { return REQUIREMENTS; }

            @Override
            public ToolExecution<String> execute(Input input, ToolExecutionContext execution) { return handler.execute(input, execution); }
        };
    }

    private static ApiToolRegistry registry(AgentTool<Input, String> tool)
    {
        return ApiToolRegistry.registered(List.of(ToolRegistration.create(tool, false, true, Optional.empty(), failure -> failure.code() + ":" + failure.message())));
    }

    private static ToolDispatcher dispatcher(AgentTool<Input, String> tool) { return new ToolDispatcher(registry(tool), Duration.ofMillis(50)); }

    private static final class Context
            implements ToolDispatcher.Context
    {
        private final Instant deadline = Instant.now().plusSeconds(60);
        private final List<String> trace = new ArrayList<>();
        private final List<ToolRegistration.Result> results = new ArrayList<>();
        private ToolRequirements grants = REQUIREMENTS;
        private boolean allow = true;
        private boolean stopped = true;
        private boolean failIntent;
        private boolean failPrepared;
        private boolean failResult;
        private boolean failCommit;
        private boolean interruptible;

        @Override
        public String invocationId() { return "request:call"; }

        @Override
        public Optional<Instant> deadline() { return Optional.of(deadline); }

        @Override
        public void checkStop() {}

        @Override
        public boolean cancelled() { return false; }

        @Override
        public ToolRequirements grants() { return grants; }

        @Override
        public boolean allowed(ToolDispatcher.Call call)
        {
            trace.add("approval");
            return allow;
        }

        @Override
        public String rejection(String code, String message) { return code + ":" + message; }

        @Override
        public void started(ToolDispatcher.Call call) { trace.add("started"); }

        @Override
        public void recordStarted(ToolDispatcher.Call call)
        {
            trace.add("intent");
            if (failIntent) {
                throw new IllegalStateException("Disk unavailable");
            }
        }

        @Override
        public void recordResult(ToolRegistration.Result result)
        {
            trace.add("result");
            if (failResult) {
                throw new IllegalStateException("Disk unavailable");
            }
            results.add(result);
        }

        @Override
        public void completed(ToolDispatcher.Call call, ToolRegistration.Result result) { trace.add("completed"); }

        @Override
        public void recordPrepared(ToolResource resource)
        {
            trace.add("prepared");
            if (failPrepared) {
                throw new IllegalStateException("Disk unavailable");
            }
        }

        @Override
        public void progress(String message) { trace.add("progress"); }

        @Override
        public void interruptible(boolean value) { interruptible = value; }
    }
}

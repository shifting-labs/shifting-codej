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
package io.github.shiftinglabs.codej;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentApiValues
{
    @Test
    void sessionRefsRoundTripThroughText()
    {
        SessionRef ref = new SessionRef("codex", "thread:123");

        assertThat(SessionRef.parse(ref.asString())).isEqualTo(ref);
        assertThatThrownBy(() -> SessionRef.parse("no-colon")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionRef("codex", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionOptionsStartFromTheSafeChoices()
    {
        SessionOptions options = SessionOptions.in(Path.of(".")).build();

        assertThat(options.workingDirectory().isAbsolute()).isTrue();
        assertThat(options.access()).isEqualTo(Access.READ_ONLY);
        assertThat(options.network()).isFalse();
        assertThat(options.approvals()).isSameAs(ApprovalHandler.DENY_ALL);
        assertThat(options.approvalTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(options.questions()).isEmpty();
        Approval request = new Approval.Other(Source.root(), "wider access", ImmutableSet.of(Decision.Kind.ALLOW_ONCE, Decision.Kind.DENY), "network");
        assertThat(ApprovalHandler.DENY_ALL.decide(request).toCompletableFuture().join()).isEqualTo(Decision.DENY);
    }

    @Test
    void sessionOptionsRejectTwoToolsWithOneNameAndNoTimeout()
    {
        Tool tool = tool("read_issue");

        assertThatThrownBy(() -> SessionOptions.in(Path.of(".")).tool(tool).tool(tool.forSubagents()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read_issue");
        assertThatThrownBy(() -> SessionOptions.in(Path.of(".")).approvalTimeout(Duration.ZERO).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolsCheckTheirNameAndCallTheirHandler()
            throws Exception
    {
        Tool tool = Tool.withSchema("echo", "Returns its arguments", "{\"type\":\"object\"}", arguments -> "echo " + arguments);

        assertThat(tool.call("{}")).isEqualTo("echo {}");
        assertThat(tool.subagents()).isFalse();
        assertThat(tool.forSubagents().subagents()).isTrue();
        assertThatThrownBy(() -> tool("Read-Issue")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesRejectWhatTheirJavadocRulesOut()
    {
        assertThatThrownBy(() -> Input.text(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Input.text("look").withAttachment(Path.of("relative.png"), "image/png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TurnOptions.defaults().withTimeLimit(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(1, 2, 0, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(1, 0, 0, Optional.of(new BigDecimal("-0.01")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Question(Source.root(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Approval.Command(Source.root(), "build", ImmutableSet.of(Decision.Kind.ALLOW_ONCE), "mvn -q verify", Path.of("/work"), true,
                ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(Decision.Kind.ALLOW_ONCE, Optional.of("why"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Decision.deny(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Source(Optional.of("agent-1"), Optional.empty(), Optional.empty()).isRoot()).isFalse();
        assertThat(Source.root().isRoot()).isTrue();
        assertThat(new Source(Optional.empty(), Optional.of("agent-1"), Optional.empty()).isRoot()).isFalse();
    }

    @Test
    void runInterruptsATurnItCanNoLongerWaitFor()
    {
        WaitInterruptedTurn turn = new WaitInterruptedTurn();

        assertThatThrownBy(() -> new OneTurnSession(turn).run(Input.text("work"))).isInstanceOf(InterruptedException.class);
        assertThat(turn.interrupted).isTrue();
    }

    private static Tool tool(String name)
    {
        return Tool.withSchema(name, "A test tool", "{}", arguments -> arguments);
    }

    private static final class WaitInterruptedTurn
            implements Turn
    {
        private boolean interrupted;

        @Override
        public CompletionStage<TurnResult> result()
        {
            return new CompletableFuture<>();
        }

        @Override
        public TurnResult await()
                throws InterruptedException
        {
            throw new InterruptedException();
        }

        @Override
        public void steer(Input input)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<TurnResult> interrupt()
        {
            interrupted = true;
            return result();
        }
    }

    private record OneTurnSession(Turn turn)
            implements Session
    {
        @Override
        public Optional<SessionRef> ref()
        {
            return Optional.of(new SessionRef("test", "1"));
        }

        @Override
        public boolean keepsProcess()
        {
            return false;
        }

        @Override
        public Turn start(Input input, TurnOptions options, Consumer<AgentEvent> listener)
        {
            return turn;
        }

        @Override
        public void close() {}
    }
}

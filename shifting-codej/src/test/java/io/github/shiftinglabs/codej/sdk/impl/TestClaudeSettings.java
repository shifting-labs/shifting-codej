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

import io.github.shiftinglabs.codej.Claude;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeSettings
{
    @TempDir
    private Path state;

    @Test
    void claudesSandboxAndReviewerOptionsReachItsSessions()
    {
        try (DriverAgent agent = (DriverAgent) Claude.builder().executable(state.resolve("claude")).stateDirectory(state)
                .runOutsideSandbox("mvn").allowUnixSocket(Path.of("/var/run/docker.sock"))
                .autoModeRules("Never push").autoModeContext("Builds reach Maven Central").reviewSandboxedCommands()
                .build()) {
            AgentPermissionScope open = agent.sandbox().apply(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false).withNetwork(true));
            assertThat(open.unsandboxedCommands()).containsExactly("mvn *");
            assertThat(open.unixSockets()).containsExactly("/var/run/docker.sock");
            assertThat(open.reviewerRules()).containsExactly("Never push");
            assertThat(open.reviewerContext()).containsExactly("Builds reach Maven Central");
            assertThat(open.reviewSandboxedCommands()).isTrue();

            // Without the network nothing reaches past the sandbox, and the reviewer still checks.
            AgentPermissionScope closed = agent.sandbox().apply(new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false));
            assertThat(closed.unsandboxedCommands()).isEmpty();
            assertThat(closed.unixSockets()).isEmpty();
            assertThat(closed.reviewerRules()).containsExactly("Never push");
        }
    }

    @Test
    void reviewerContextNeedsARule()
    {
        assertThatThrownBy(() -> Claude.builder().stateDirectory(state).autoModeContext("Builds reach Maven Central").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Auto-mode context needs a rule");
    }
}

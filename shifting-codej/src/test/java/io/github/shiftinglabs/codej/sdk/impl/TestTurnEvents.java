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

import io.github.shiftinglabs.codej.AgentEvent;
import io.github.shiftinglabs.codej.Source;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestTurnEvents
{
    @ParameterizedTest
    @CsvSource({"AGENT_COMPLETED,COMPLETED", "AGENT_FAILED,FAILED", "AGENT_STOPPED,STOPPED"})
    void endsASubagentAsItsDriverReports(ProviderAgentEvent.LifecyclePhase phase, AgentEvent.Activity activity)
    {
        Optional<String> thread = Optional.of("child");
        Optional<String> parent = Optional.of("root-thread");
        Optional<String> label = Optional.of("explorer");
        ProviderAgentEventSource child = new ProviderAgentEventSource(thread, parent, label, Optional.empty(), Optional.empty());

        assertThat(new TurnEvents(_ -> Optional.empty()).map(new ProviderAgentEvent.Lifecycle(Optional.empty(), phase, "explorer", child)))
                .contains(new AgentEvent.Subagent(new Source(thread, parent, label), activity));
    }
}

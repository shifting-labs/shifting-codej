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
package io.github.shiftinglabs.codej.tool.consumer;

import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.tool.testing.AbstractTestAgentTool;
import io.github.shiftinglabs.codej.tool.testing.ToolContractFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;

class TestAgentTool
{
    abstract static class Binding
            extends AbstractTestAgentTool<ToolContractFixtures.LookupInput, ToolContractFixtures.LookupResult>
    {
        private ToolContractFixtures.Fixture fixture;

        protected abstract boolean alternate();

        @BeforeEach
        void setup() { fixture = new ToolContractFixtures.Fixture(alternate()); }

        @Override
        protected AgentTool<ToolContractFixtures.LookupInput, ToolContractFixtures.LookupResult> tool() { return fixture.tool; }

        @Override
        protected ToolContractFixtures.LookupInput input() { return new ToolContractFixtures.LookupRequest("ISSUE-1"); }

        @Override
        protected ToolContractFixtures.LookupResult expected() { return new ToolContractFixtures.LookupResult("ISSUE-1", "Issue ISSUE-1"); }

        @Override
        protected ToolRequirements requirements() { return ToolContractFixtures.REQUIREMENTS; }

        @Override
        protected int calls() { return fixture.calls.get(); }

        @Override
        protected void failBackend() { fixture.fail.set(true); }

        @Override
        protected ToolFailure expectedFailure() { return ToolContractFixtures.FAILURE; }

        @Override
        protected boolean preparesExternalResource() { return true; }
    }

    @Nested
    class ReferenceBackend
            extends Binding
    {
        @Override
        protected boolean alternate() { return false; }
    }

    @Nested
    class AlternateBackend
            extends Binding
    {
        @Override
        protected boolean alternate() { return true; }
    }
}

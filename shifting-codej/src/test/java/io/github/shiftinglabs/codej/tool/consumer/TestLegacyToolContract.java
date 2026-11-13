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

import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.api.tools.LegacyToolAdapter;
import io.github.shiftinglabs.codej.cli.spi.tool.AgentTool;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolCapability;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolFailure;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import io.github.shiftinglabs.codej.tool.testing.AbstractTestAgentTool;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class TestLegacyToolContract
        extends AbstractTestAgentTool<LegacyToolAdapter.Arguments, String>
{
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean failing = new AtomicBoolean();
    private LegacyToolAdapter tool;

    @BeforeEach
    void setup()
    {
        tool = LegacyToolAdapter.builder(Tool.withSchema("hello", "Return a greeting", "{}", input -> {
            calls.incrementAndGet();
            if (failing.get()) {
                throw new IllegalArgumentException("private backend diagnostic");
            }
            return "hello";
        })).build();
    }

    @Override
    protected AgentTool<LegacyToolAdapter.Arguments, String> tool() { return tool; }

    @Override
    protected LegacyToolAdapter.Arguments input() { return new LegacyToolAdapter.Arguments("{}"); }

    @Override
    protected String expected() { return "hello"; }

    @Override
    protected ToolRequirements requirements() { return ToolRequirements.builder().require(ToolCapability.HOST_INVOKE).build(); }

    @Override
    protected int calls() { return calls.get(); }

    @Override
    protected void failBackend() { failing.set(true); }

    @Override
    protected ToolFailure expectedFailure() { return new ToolFailure("TOOL_FAILED", "Host tool failed"); }
}

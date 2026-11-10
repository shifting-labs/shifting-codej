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
package io.github.shiftinglabs.codej.cli.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestToolServer
{
    @Test
    void derivesEveryNameAModelSeesFromTheServerName()
    {
        ToolServer server = new ToolServer("my_app2");

        assertThat(server.qualified("report")).isEqualTo("mcp__my_app2__report");
        assertThat(server.listTools()).isEqualTo("my_app2_list_tools");
        assertThat(server.getTool()).isEqualTo("my_app2_get_tool");
        assertThat(server.callTool()).isEqualTo("my_app2_call_tool");
        assertThat(server.gateway()).isEqualTo("my_app2_gateway");
        assertThat(ToolServer.DEFAULT.name()).isEqualTo("codej");
    }

    @Test
    void refusesNamesThatWouldBreakToolNames()
    {
        for (String name : new String[] {"", "App", "my-app", "my__app", "_app", "app_", "2app", "a".repeat(33)}) {
            assertThatThrownBy(() -> new ToolServer(name)).as(name).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new ToolServer("a".repeat(32)).name()).hasSize(32);
    }
}

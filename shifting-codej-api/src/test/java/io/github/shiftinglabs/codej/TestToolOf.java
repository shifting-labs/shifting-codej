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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolOf
{
    enum Priority
    {
        LOW,
        HIGH
    }

    record Label(String name, boolean visible) {}

    record Issue(String title, int votes, long id, double weight, Priority priority, List<String> tags, List<Label> labels, Optional<String> assignee, Label primary) {}

    record At(Instant when) {}

    @Test
    void aRecordsComponentsBecomeTheSchema()
            throws Exception
    {
        Tool tool = Tool.of("file_issue", "Files an issue", Issue.class, arguments -> "filed");
        assertThat(new ObjectMapper().readTree(tool.inputSchema())).isEqualTo(new ObjectMapper().readTree("""
                {"type":"object","properties":{
                  "title":{"type":"string"},
                  "votes":{"type":"integer"},
                  "id":{"type":"integer"},
                  "weight":{"type":"number"},
                  "priority":{"type":"string","enum":["LOW","HIGH"]},
                  "tags":{"type":"array","items":{"type":"string"}},
                  "labels":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"visible":{"type":"boolean"}},
                    "required":["name","visible"],"additionalProperties":false}},
                  "assignee":{"type":"string"},
                  "primary":{"type":"object","properties":{"name":{"type":"string"},"visible":{"type":"boolean"}},
                    "required":["name","visible"],"additionalProperties":false}},
                 "required":["title","votes","id","weight","priority","tags","labels","primary"],
                 "additionalProperties":false}
                """));
        assertThat(tool.name()).isEqualTo("file_issue");
        assertThat(tool.subagents()).isFalse();
    }

    @Test
    void eachCallBindsIntoANewRecord()
            throws Exception
    {
        AtomicReference<Issue> received = new AtomicReference<>();
        Tool tool = Tool.of("file_issue", "Files an issue", Issue.class, arguments -> {
            received.set(arguments);
            return "filed " + arguments.title();
        });
        String call = """
                {"title":"Crash","votes":3,"id":12,"weight":0.5,"priority":"HIGH","tags":["ui"],"labels":[{"name":"bug","visible":true}],
                 "primary":{"name":"bug","visible":false}}
                """;
        assertThat(tool.call(call)).isEqualTo("filed Crash");
        assertThat(received.get()).isEqualTo(new Issue("Crash", 3, 12, 0.5, Priority.HIGH, ImmutableList.of("ui"), ImmutableList.of(new Label("bug", true)), Optional.empty(),
                new Label("bug", false)));
    }

    @Test
    void aBadCallFailsWithAMessageTheAgentCanFixItFrom()
    {
        Tool tool = Tool.of("label", "Labels", Label.class, arguments -> "ok");
        assertThatThrownBy(() -> tool.call("{\"visible\":true}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing argument: name");
        assertThatThrownBy(() -> tool.call("{\"name\":\"bug\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid arguments: ");
        assertThatThrownBy(() -> tool.call("{\"name\":\"bug\",\"visible\":true,\"color\":\"red\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("color");
        assertThatThrownBy(() -> tool.call("{\"name\":7,\"visible\":\"yes\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid arguments: ");
    }

    @Test
    void aComponentOfAnotherTypeIsRefused()
    {
        assertThatThrownBy(() -> Tool.of("at", "At a time", At.class, arguments -> "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("when");
    }
}

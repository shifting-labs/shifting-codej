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
package io.github.shiftinglabs.codej.usage;

import io.github.shiftinglabs.codej.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Records as callers write them, outside the API's package and seldom public. */
final class TestToolsOfCallerRecords
{
    record IssueKey(String key) {}

    private record Comment(IssueKey issue, String text) {}

    @Test
    void callsAToolWhoseArgumentsAreAPackagePrivateRecord()
            throws Exception
    {
        Tool readIssue = Tool.of("read_issue", "Reads one issue by its key, such as ENG-1", IssueKey.class, issue -> "issue " + issue.key());

        assertThat(readIssue.call("{\"key\":\"ENG-1\"}")).isEqualTo("issue ENG-1");
    }

    @Test
    void callsAToolWhoseArgumentsAreAPrivateRecordHoldingAnother()
            throws Exception
    {
        Tool comment = Tool.of("comment", "Comments on an issue", Comment.class, call -> call.issue().key() + ": " + call.text());

        assertThat(comment.call("{\"issue\":{\"key\":\"ENG-1\"},\"text\":\"Looks right\"}")).isEqualTo("ENG-1: Looks right");
        assertThatThrownBy(() -> comment.call("{\"issue\":{},\"text\":\"Looks right\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing argument: key");
    }
}

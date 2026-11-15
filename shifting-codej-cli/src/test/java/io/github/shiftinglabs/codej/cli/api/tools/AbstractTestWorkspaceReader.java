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
package io.github.shiftinglabs.codej.cli.api.tools;

import com.google.common.hash.Hashing;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared read-resource behavior; physical containment has additional backend-specific tests. */
public abstract class AbstractTestWorkspaceReader
{
    public interface Fixture
            extends AutoCloseable
    {
        WorkspaceReader reader();

        WorkspaceEditor editor();

        void seed(String path, String text)
                throws Exception;

        Optional<String> content(String path)
                throws Exception;

        @Override
        void close()
                throws Exception;
    }

    protected abstract Fixture open(boolean writable)
            throws Exception;

    static ResourceTestContext readContext(String path) { return new ResourceTestContext(ToolRequirements.builder().read(Path.of(path)).build()); }

    @Test
    void readsUtf8ExcerptsWithTheWholeFilePreimage()
            throws Exception
    {
        try (Fixture fixture = open(false)) {
            String text = "first\nsecond\n界 third";
            fixture.seed("source", text);
            var full = fixture.reader().read(new ToolMessages.ReadFile("source"), readContext("source"));
            assertThat(full.text()).isEqualTo(text);
            assertThat(full.sha256()).isEqualTo(Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString());
            var excerpt = fixture.reader().read(new ToolMessages.ReadFile("source", Optional.of(2), Optional.of(6)), readContext("source"));
            assertThat(excerpt.text()).isEqualTo("second");
            assertThat(excerpt.firstLine()).isEqualTo(2);
            assertThat(excerpt.sha256()).isEqualTo(full.sha256());
            assertThat(excerpt.truncated()).isTrue();
        }
    }

    @Test
    void listsAndSearchesWithExplicitBoundsAndLiteralCaseRules()
            throws Exception
    {
        try (Fixture fixture = open(false)) {
            fixture.seed("one", "A.b\nA.b");
            fixture.seed("two", "other");
            assertThat(fixture.reader().list(new ToolMessages.ListFiles(".", 10), readContext(".")).entries())
                    .extracting(ToolMessages.FileEntry::path).containsExactlyInAnyOrder("one", "two");
            assertThat(fixture.reader().list(new ToolMessages.ListFiles(".", 1), readContext(".")).truncated()).isTrue();
            assertThat(fixture.reader().search(new ToolMessages.SearchFiles(".", "a.B", false, 1), readContext(".")).matches())
                    .containsExactly(new ToolMessages.SearchMatch("one", 1, "A.b"));
            assertThat(fixture.reader().search(new ToolMessages.SearchFiles(".", "a.B", true, 10), readContext(".")).matches()).isEmpty();
            assertThat(fixture.reader().search(new ToolMessages.SearchFiles(".", "Axb", false, 10), readContext(".")).matches()).isEmpty();
        }
    }

    @Test
    void rejectsMissingAndWrongPathGrantsBeforeFilesystemAccess()
            throws Exception
    {
        try (Fixture fixture = open(false)) {
            var denied = new ResourceTestContext(ToolRequirements.none());
            var elsewhere = readContext("elsewhere");
            for (var context : List.of(denied, elsewhere)) {
                assertThatThrownBy(() -> fixture.reader().read(new ToolMessages.ReadFile("missing"), context)).isInstanceOf(SecurityException.class);
                assertThatThrownBy(() -> fixture.reader().list(new ToolMessages.ListFiles(".", 1), context)).isInstanceOf(SecurityException.class);
                assertThatThrownBy(() -> fixture.reader().search(new ToolMessages.SearchFiles(".", "value", true, 1), context)).isInstanceOf(SecurityException.class);
            }
        }
    }

    @Test
    void cancelledAndExpiredReadCallsDoNotAccessTheResource()
            throws Exception
    {
        try (Fixture fixture = open(false)) {
            var cancelled = readContext(".");
            cancelled.cancelled = true;
            var expired = readContext(".");
            expired.deadline = Optional.of(Instant.EPOCH);
            for (var context : List.of(cancelled, expired)) {
                assertThatThrownBy(() -> fixture.reader().read(new ToolMessages.ReadFile("missing"), context)).isInstanceOf(CancellationException.class);
                assertThatThrownBy(() -> fixture.reader().list(new ToolMessages.ListFiles(".", 1), context)).isInstanceOf(CancellationException.class);
                assertThatThrownBy(() -> fixture.reader().search(new ToolMessages.SearchFiles(".", "value", true, 1), context)).isInstanceOf(CancellationException.class);
            }
        }
    }
}

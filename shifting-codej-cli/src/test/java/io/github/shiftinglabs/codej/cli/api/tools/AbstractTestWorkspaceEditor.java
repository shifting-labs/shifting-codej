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

import io.github.shiftinglabs.codej.cli.spi.tool.ToolRequirements;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ApplyPatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.Edit;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.PatchAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared edit-resource behavior, independent of filesystem handles or an implementation class. */
public abstract class AbstractTestWorkspaceEditor
{
    protected abstract AbstractTestWorkspaceReader.Fixture open(boolean writable)
            throws Exception;

    static ResourceTestContext writeContext(String path) { return new ResourceTestContext(ToolRequirements.builder().write(Path.of(path)).build()); }

    @Test
    void createUpdateAndDeleteUsePreimagesWithoutGrantingGeneralReads()
            throws Exception
    {
        try (var fixture = open(true)) {
            var context = writeContext("code");
            var created = fixture.editor().apply(new ApplyPatch("code", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "hello world"))), context);
            var updated = fixture.editor().apply(new ApplyPatch("code", PatchAction.UPDATE, created.sha256(), List.of(new Edit("world", "engine"))), context);
            assertThat(fixture.content("code")).contains("hello engine");
            assertThat(updated.sha256()).isNotEqualTo(created.sha256());
            var deleted = fixture.editor().apply(new ApplyPatch("code", PatchAction.DELETE, updated.sha256(), List.of()), context);
            assertThat(deleted.sha256()).isEmpty();
            assertThat(fixture.content("code")).isEmpty();
            assertThat(context.grants().reads()).isEmpty();
        }
    }

    @Test
    void staleAmbiguousAndCreateOverExistingPatchesLeaveContentUnchanged()
            throws Exception
    {
        try (var fixture = open(true)) {
            fixture.seed("code", "one two two");
            String hash = fixture.reader().read(new ToolMessages.ReadFile("code"), AbstractTestWorkspaceReader.readContext("code")).sha256();
            var context = writeContext("code");
            assertThatThrownBy(() -> fixture.editor().apply(new ApplyPatch("code", PatchAction.DELETE, Optional.of("0".repeat(64)), List.of()), context))
                    .isInstanceOf(CodingToolException.class);
            assertThatThrownBy(() -> fixture.editor().apply(new ApplyPatch("code", PatchAction.UPDATE, Optional.of(hash),
                    List.of(new Edit("one", "changed"), new Edit("two", "ambiguous"))), context)).isInstanceOf(CodingToolException.class);
            assertThatThrownBy(() -> fixture.editor().apply(new ApplyPatch("code", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "changed"))), context))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(fixture.content("code")).contains("one two two");
        }
    }

    @Test
    void scopedGrantsCancellationAndDeadlineAreEnforcedBeforeWrites()
            throws Exception
    {
        try (var fixture = open(true)) {
            var request = new ApplyPatch("new", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "content")));
            for (var context : List.of(new ResourceTestContext(ToolRequirements.none()), writeContext("elsewhere"))) {
                assertThatThrownBy(() -> fixture.editor().apply(request, context)).isInstanceOf(SecurityException.class);
            }
            var cancelled = writeContext("new");
            cancelled.cancelled = true;
            var expired = writeContext("new");
            expired.deadline = Optional.of(Instant.EPOCH);
            for (var context : List.of(cancelled, expired)) {
                assertThatThrownBy(() -> fixture.editor().apply(request, context)).isInstanceOf(CancellationException.class);
            }
            assertThat(fixture.content("new")).isEmpty();
        }
    }

    @Test
    void invocationGrantsCannotWidenAReadOnlyBackend()
            throws Exception
    {
        try (var fixture = open(false)) {
            var request = new ApplyPatch("new", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "content")));
            assertThatThrownBy(() -> fixture.editor().apply(request, writeContext("new"))).isInstanceOf(CodingToolException.class).hasMessageContaining("cannot change");
            assertThat(fixture.content("new")).isEmpty();
        }
    }
}

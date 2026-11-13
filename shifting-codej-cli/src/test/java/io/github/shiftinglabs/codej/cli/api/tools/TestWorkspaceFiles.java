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

import io.github.shiftinglabs.codej.CodingTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ApplyPatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.Edit;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.FileContent;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.FileEntry;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ListFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.PatchAction;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.PatchResult;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ReadFile;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchFiles;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchMatch;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.SearchResult;
import static io.github.shiftinglabs.codej.cli.api.tools.ToolMessages.ShellCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorkspaceFiles
{
    @TempDir
    Path directory;

    private WorkspaceFiles workspace(boolean writable)
            throws Exception
    {
        Path root = directory.resolve("work");
        Files.createDirectories(root);
        return new WorkspaceFiles(root, CodingTools.defaults(), writable, List.of(root.resolve("private")));
    }

    @Test
    void readListSearchAndPatchUsePreimages()
            throws Exception
    {
        try (WorkspaceFiles files = workspace(true)) {
            ApplyPatch create = new ApplyPatch("hello.txt", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "Hello world\nsecond line\n")));
            PatchResult created = files.patch(create);
            FileContent original = files.read(new ReadFile("hello.txt"));
            assertThat(original.sha256()).isEqualTo(created.sha256().orElseThrow());
            assertThat(original.text()).isEqualTo("Hello world\nsecond line\n");
            assertThat(files.list(new ListFiles(".", 10)).entries()).containsExactly(new FileEntry("hello.txt", ToolMessages.FileKind.FILE));
            SearchResult found = files.search(new SearchFiles(".", "HELLO", false, 10));
            assertThat(found.matches()).containsExactly(new SearchMatch("hello.txt", 1, "Hello world"));
            assertThat(found.truncated()).isFalse();
            assertThat(files.search(new SearchFiles(".", "HELLO", true, 10)).matches()).isEmpty();
            PatchResult updated = files.patch(new ApplyPatch("hello.txt", PatchAction.UPDATE, Optional.of(original.sha256()), List.of(new Edit("world", "engine"))));
            assertThat(files.read(new ReadFile("hello.txt")).text()).contains("Hello engine");
            assertThatThrownBy(() -> files.patch(new ApplyPatch("hello.txt", PatchAction.DELETE, Optional.of(original.sha256()), List.of())))
                    .isInstanceOf(CodingToolException.class).hasMessageContaining("expectedSha256");
            files.patch(new ApplyPatch("hello.txt", PatchAction.DELETE, updated.sha256(), List.of()));
            assertThat(files.root().resolve("hello.txt")).doesNotExist();
        }
    }

    @Test
    void rejectsTraversalSymlinksHardlinksAndProtectedPaths()
            throws Exception
    {
        Files.writeString(directory.resolve("outside"), "private");
        try (WorkspaceFiles files = workspace(true)) {
            Files.createSymbolicLink(files.root().resolve("alias"), directory);
            Files.createLink(files.root().resolve("hard"), directory.resolve("outside"));
            for (String path : List.of("alias/outside", "hard", ".git/config", "private/key", ".ssh/key", ".config/gh/hosts.yml", ".claude/settings.json", ".codej-state/token")) {
                assertThatThrownBy(() -> files.read(new ReadFile(path))).isInstanceOfAny(CodingToolException.class, java.io.IOException.class);
                assertThatThrownBy(() -> files.patch(new ApplyPatch(path, PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "changed")))))
                        .isInstanceOfAny(CodingToolException.class, java.io.IOException.class);
            }
            assertThat(files.search(new SearchFiles(".", "private", true, 10)).matches()).isEmpty();
            assertThat(Files.readString(directory.resolve("outside"))).isEqualTo("private");
            assertThatThrownBy(() -> new ReadFile("../outside")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ReadFile("/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsWritesInReadOnlyScopeAndConcurrentOwners()
            throws Exception
    {
        try (WorkspaceFiles files = workspace(false)) {
            assertThatThrownBy(() -> files.patch(new ApplyPatch("new", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "content")))))
                    .isInstanceOf(CodingToolException.class).hasMessageContaining("cannot change");
            assertThatThrownBy(() -> workspace(true)).isInstanceOf(RuntimeException.class);
        }
        try (WorkspaceFiles reopened = workspace(true)) {
            assertThat(reopened.root()).isDirectory();
        }
    }

    @Test
    void validatesWholePatchBeforeReplacingAndPreservesPermissions()
            throws Exception
    {
        try (WorkspaceFiles files = workspace(true)) {
            Path path = files.root().resolve("code");
            Files.writeString(path, "one two two");
            var permissions = Files.getPosixFilePermissions(path);
            String hash = files.read(new ReadFile("code")).sha256();
            assertThatThrownBy(() -> files.patch(new ApplyPatch("code", PatchAction.UPDATE, Optional.of(hash),
                    List.of(new Edit("one", "changed"), new Edit("two", "ambiguous")))))
                    .isInstanceOf(CodingToolException.class).hasMessageContaining("exactly once");
            assertThat(Files.readString(path)).isEqualTo("one two two");
            assertThatThrownBy(() -> files.patch(new ApplyPatch("code", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "overwrite")))))
                    .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
            files.patch(new ApplyPatch("code", PatchAction.UPDATE, Optional.of(hash), List.of(new Edit("one", "changed"))));
            assertThat(Files.getPosixFilePermissions(path)).isEqualTo(permissions);
            try (var entries = Files.list(files.root())) {
                assertThat(entries.map(p -> p.getFileName().toString()).toList()).containsExactly("code");
            }
        }
    }

    @Test
    void boundsSearchAndRejectsBinaryAndOversizeFiles()
            throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("work"));
        Files.writeString(root.resolve("a"), "match\nmatch\nmatch");
        Files.write(root.resolve("binary"), new byte[] {0, 1, 2});
        Files.writeString(root.resolve("large"), "x".repeat(65));
        CodingTools limits = new CodingTools(64, 64, 10, 1, 256);
        try (WorkspaceFiles files = new WorkspaceFiles(root, limits, true, List.of())) {
            assertThat(files.search(new SearchFiles(".", "match", true, 1)).truncated()).isTrue();
            assertThat(files.list(new ListFiles(".", 1)).truncated()).isTrue();
            assertThatThrownBy(() -> files.read(new ReadFile("binary"))).isInstanceOf(CodingToolException.class).hasMessageContaining("UTF-8");
            assertThatThrownBy(() -> files.read(new ReadFile("large"))).isInstanceOf(CodingToolException.class).hasMessageContaining("byte limit");
            assertThatThrownBy(() -> files.patch(new ApplyPatch("new", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "x".repeat(65))))))
                    .isInstanceOf(CodingToolException.class);
            assertThat(root.resolve("new")).doesNotExist();
        }
    }

    @Test
    void privateStateExclusionsResolveAliasesAndFutureDirectories()
            throws Exception
    {
        Path root = Files.createDirectory(directory.resolve("work"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), root);
        try (WorkspaceFiles files = new WorkspaceFiles(root, CodingTools.defaults(), true, List.of(alias.resolve("future")))) {
            Files.createDirectory(root.resolve("future"));
            Files.writeString(root.resolve("future/token"), "private");
            assertThatThrownBy(() -> files.read(new ReadFile("future/token"))).isInstanceOf(CodingToolException.class).hasMessageContaining("protected");
            assertThat(files.search(new SearchFiles(".", "private", true, 10)).matches()).isEmpty();
            assertThat(files.sandboxDenials()).contains(root.toRealPath().resolve("future"));
        }
    }

    @Test
    void excerptsKeepTheWholeFilePreimageAndInvalidUnicodeNeverWrites()
            throws Exception
    {
        try (WorkspaceFiles files = workspace(true)) {
            Files.writeString(files.root().resolve("lines"), "first\nsecond\nthird");
            FileContent full = files.read(new ReadFile("lines"));
            FileContent excerpt = files.read(new ReadFile("lines", Optional.of(2), Optional.of(6)));
            assertThat(excerpt.text()).isEqualTo("second");
            assertThat(excerpt.firstLine()).isEqualTo(2);
            assertThat(excerpt.sha256()).isEqualTo(full.sha256());
            assertThat(excerpt.truncated()).isTrue();
            assertThatThrownBy(() -> files.patch(new ApplyPatch("bad", PatchAction.CREATE, Optional.empty(), List.of(new Edit("", "\ud800")))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unicode");
            assertThat(files.root().resolve("bad")).doesNotExist();
        }
    }

    @Test
    void defensiveInputRecordsAndSchemas()
    {
        assertThatThrownBy(() -> new CodingTools(0, 1, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchFiles(".", "", true, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShellCommand(List.of("relative"), ".", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplyPatch("x", PatchAction.DELETE, Optional.of("bad"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplyPatch("x", PatchAction.UPDATE, Optional.of("a".repeat(64)), List.of(new Edit("", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BuiltinTools.definitions(false)).extracting(tool -> tool.name()).doesNotContain("apply_patch");
        assertThat(BuiltinTools.definitions(true)).hasSize(5);
        assertThat(BuiltinTools.failure("DENIED", "blocked")).isEqualTo("{\"code\":\"DENIED\",\"message\":\"blocked\"}");
    }
}

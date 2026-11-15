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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorkspaceSnapshot
{
    @TempDir
    Path directory;

    private ResourceTestContext context() { return new ResourceTestContext(AbstractTestCommandExecutor.grants()); }

    @Test
    void exportsBinaryAndExecutableFilesWhileOmittingProtectedPathsAndLinks()
            throws Exception
    {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.createDirectories(source.resolve("src"));
        Files.write(source.resolve("src/binary"), new byte[] {0, 1, (byte) 0xFF});
        Files.writeString(source.resolve("script"), "#!/bin/sh\ntrue\n");
        Files.setPosixFilePermissions(source.resolve("script"), PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(source.resolve(".git"));
        Files.writeString(source.resolve(".git/secret"), "secret");
        Files.writeString(source.resolve("excluded"), "secret");
        Files.createSymbolicLink(source.resolve("link"), source.resolve("script"));
        Files.createLink(source.resolve("hardlink"), source.resolve("excluded"));
        try (var leased = new WorkspaceFiles(source, CodingTools.defaults(), true, List.of());
                var snapshot = WorkspaceFiles.snapshotSource(source, CodingTools.defaults(), List.of(source.resolve("excluded")))) {
            leased.verifyRoot();
            snapshot.snapshot(target, context());
        }
        assertThat(Files.readAllBytes(target.resolve("src/binary"))).containsExactly((byte) 0, (byte) 1, (byte) 0xFF);
        assertThat(Files.getPosixFilePermissions(target.resolve("script"))).isEqualTo(PosixFilePermissions.fromString("r-x------"));
        assertThat(Files.exists(target.resolve(".git"))).isFalse();
        assertThat(Files.exists(target.resolve("excluded"))).isFalse();
        assertThat(Files.exists(target.resolve("hardlink"))).isFalse();
        assertThat(Files.exists(target.resolve("link"))).isFalse();
    }

    @Test
    void enforcesAggregateFileAndEntryBudgets()
            throws Exception
    {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("one"), "1234");
        Files.writeString(source.resolve("two"), "5678");
        for (CodingTools limits : List.of(new CodingTools(3, 8, 10, 1, 1), new CodingTools(4, 7, 10, 1, 1), new CodingTools(4, 8, 1, 1, 1))) {
            Path target = Files.createTempDirectory(directory, "target");
            try (var snapshot = WorkspaceFiles.snapshotSource(source, limits, List.of())) {
                assertThatThrownBy(() -> snapshot.snapshot(target, context())).isInstanceOf(CodingToolException.class);
            }
        }
    }

    @Test
    void cancellationAndRootReplacementPreventExport()
            throws Exception
    {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path target = Files.createDirectory(directory.resolve("target"));
        try (var snapshot = WorkspaceFiles.snapshotSource(source, CodingTools.defaults(), List.of())) {
            var context = context();
            context.cancelled = true;
            assertThatThrownBy(() -> snapshot.snapshot(target, context)).isInstanceOf(CancellationException.class);
            Files.move(source, directory.resolve("moved"));
            Files.createDirectory(source);
            assertThatThrownBy(() -> snapshot.snapshot(target, context())).isInstanceOf(CodingToolException.class);
        }
    }
}

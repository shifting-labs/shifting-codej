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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestNativeCommandRegistry
{
    @TempDir
    Path profile;
    private final String boot = UUID.randomUUID().toString();

    @Test
    void liveOwnersAreSkippedAndCommittedEntriesAreRemoved()
            throws Exception
    {
        try (var first = new NativeCommandRegistry(profile, entry -> { throw new AssertionError("live owner"); })) {
            var entry = first.create(boot);
            try (var second = new NativeCommandRegistry(profile, old -> { throw new AssertionError("live owner"); })) {
                var other = second.create(boot);
                other.stopped();
                other.remove(100);
            }
            entry.stopped();
            entry.remove(100);
            assertThat(Files.exists(Path.of(entry.state().directory()))).isFalse();
        }
        try (var files = Files.list(profile.resolve("native-command-state"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void abandonedIntentIsTypedAndRecoveredBeforeNewOwnership()
            throws Exception
    {
        NativeCommandRegistry.Entry saved;
        try (var registry = new NativeCommandRegistry(profile, entry -> {})) {
            saved = registry.create(boot);
            saved.launch("launch-1", true);
            saved.control("control-1", true);
            saved.registered("/user.slice/" + saved.state().unit());
            saved.released();
            saved.registered("");
            assertThat(saved.state().group()).isEqualTo("/user.slice/" + saved.state().unit());
            NativeCommandRegistry.Entry registered = saved;
            assertThatThrownBy(() -> registered.registered("/different/group")).isInstanceOf(IOException.class);
            Files.writeString(Path.of(saved.state().directory()).resolve("artifact"), "payload");
        }
        List<NativeCommandRegistry.State> seen = new ArrayList<>();
        try (var registry = new NativeCommandRegistry(profile, entry -> {
            seen.add(entry.state());
            entry.stopped();
            NativeCommandRegistry.cleanup(entry.state(), 100);
        })) {
            assertThat(seen).containsExactly(saved.state());
            assertThat(Files.exists(Path.of(saved.state().directory()))).isFalse();
            assertThat(seen.getFirst().toString()).doesNotContain(saved.state().directory(), "launch-1");
            var current = registry.create(boot);
            current.stopped();
            current.remove(100);
        }
        try (var files = Files.walk(profile)) {
            List<Path> receipts = files.filter(path -> path.toString().endsWith(".json")).toList();
            assertThat(receipts).hasSize(1);
            assertThat(Files.readString(receipts.getFirst())).contains("\"stopped\":true").doesNotContain("payload");
        }
    }

    @Test
    void failedRecoveryRetainsReceiptAndArtifactsForRetry()
            throws Exception
    {
        NativeCommandRegistry.Entry saved;
        try (var registry = new NativeCommandRegistry(profile, entry -> {})) {
            saved = registry.create(boot);
            saved.launch("launch-1", true);
        }
        assertThatThrownBy(() -> new NativeCommandRegistry(profile, entry -> { throw new IOException("unverified"); })).isInstanceOf(IOException.class);
        assertThat(Files.exists(Path.of(saved.state().directory()))).isTrue();
        try (var registry = new NativeCommandRegistry(profile, entry -> {
            entry.stopped();
            entry.remove(100);
        })) {
            var current = registry.create(boot);
            current.stopped();
            current.remove(100);
        }
    }

    @Test
    void cleanupNeverFollowsLinksAndRefusesChangedArtifactIdentity()
            throws Exception
    {
        Path outside = Files.writeString(profile.resolve("keep"), "safe");
        try (var registry = new NativeCommandRegistry(profile, entry -> {})) {
            var entry = registry.create(boot);
            Path root = Path.of(entry.state().directory());
            Files.createSymbolicLink(root.resolve("link"), outside);
            assertThatThrownBy(() -> entry.remove(100)).isInstanceOf(IOException.class);
            Path moved = root.resolveSibling(root.getFileName() + "-moved");
            Files.move(root, moved);
            Files.createDirectory(root);
            assertThatThrownBy(() -> NativeCommandRegistry.cleanup(entry.state(), 100)).isInstanceOf(IOException.class);
            Files.delete(root);
            Files.move(moved, root);
            entry.stopped();
            entry.remove(100);
        }
        assertThat(Files.readString(outside)).isEqualTo("safe");
    }
}

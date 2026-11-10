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
package io.github.shiftinglabs.codej.cli.process;

import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.cli.TaskScopes;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentPermissionManager
{
    @TempDir
    private Path temporary;

    @Test
    void developmentAndCiAllowConcreteWritesOnlyWithinVerifiedRoots()
            throws Exception
    {
        Path worktree = Files.createDirectory(temporary.resolve("worktree"));
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(worktree.resolve("escape"), outside);
        List<Path> caches = TaskScopes.buildCaches(home);
        AgentPermissionScope development = TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false).withWritableRoots(caches);
        AgentPermissionScope ci = TaskScopes.of(AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL, false, false).withWritableRoots(caches);
        AgentPermissionScope cherryPick = TaskScopes.of(AgentPermissionPolicy.CHERRY_PICK, SessionAccess.FULL, false, false);

        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree, development)).isTrue();
        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".m2/settings.xml"), worktree, ci)).isTrue();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("escape/secret"), worktree, development)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(outside.resolve("secret"), worktree, ci)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".ssh/id_ed25519"), worktree, development)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree, cherryPick)).isTrue();
        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".m2/repository/test.pom"), worktree, cherryPick)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(Path.of("relative"), worktree, development)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree,
                TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, true, false))).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree,
                new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false))).isFalse();
    }

    @Test
    void linkedCacheRootDoesNotGrantItsTarget()
            throws Exception
    {
        Path worktree = Files.createDirectory(temporary.resolve("worktree"));
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(home.resolve(".m2"), outside);

        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".m2/settings.xml"), worktree,
                TaskScopes.of(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false, false).withWritableRoots(ImmutableList.of(home.resolve(".m2"))))).isFalse();
    }
}

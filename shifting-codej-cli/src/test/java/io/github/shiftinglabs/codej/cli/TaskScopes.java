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
package io.github.shiftinglabs.codej.cli;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.github.shiftinglabs.codej.cli.driver.AgentCommandPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.AgentSessionContext;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The settings the host application's turn service gives a task session, for driver tests that read them. */
public final class TaskScopes
{
    private TaskScopes() {}

    public static AgentPermissionScope of(AgentPermissionPolicy policy, SessionAccess access, boolean review, boolean publicationProtected)
    {
        boolean full = access == SessionAccess.FULL;
        AgentPermissionScope base = new AgentPermissionScope(policy, access, review, publicationProtected);
        return AgentCommandPolicy.apply(base
                .withNetwork(full && policy != AgentPermissionPolicy.CHERRY_PICK)
                .withUnsandboxedCommands(full && policy != AgentPermissionPolicy.CHERRY_PICK && !review ? ImmutableList.of("mvn *", "./mvnw *", "mvnd *") : ImmutableList.of())
                .withUnixSockets(full && policy != AgentPermissionPolicy.CHERRY_PICK
                        ? ImmutableList.of("/var/run/docker.sock", "~/.docker/run/docker.sock", "~/.orbstack/run/docker.sock") : ImmutableList.of())
                .withWebSearch(full && !policy.unattended())
                .withNoSubagents(policy.unattended() || review && !base.reviewReads())
                .withReadOnlySubagents(review)
                .withApproveEveryCommand(policy == AgentPermissionPolicy.CHERRY_PICK)
                .withFixedSandbox(policy == AgentPermissionPolicy.CI_AUTOFIX)
                .withCommits(full && !review)
                .withApproveEveryEdit(review));
    }

    /** This context with the settings the host application gives its scope. */
    public static AgentSessionContext of(AgentSessionContext context)
    {
        return context.withTaskScope(of(context.permissionPolicy(), context.access(), context.review(), context.publicationProtected()), context.asksUser());
    }

    /**
     * This context as the host application gives it: its builds may fill the caches under {@code home} when they may download, and an
     * attended session may use the host's temporary folders.
     */
    public static AgentSessionContext of(AgentSessionContext context, Path home)
    {
        AgentSessionContext task = of(context);
        AgentPermissionScope scope = task.scope();
        List<Path> roots = ImmutableList.<Path>builder()
                .addAll(scope.network() ? buildCaches(home) : ImmutableList.of())
                .addAll(scope.access() == SessionAccess.FULL && !scope.unattended() ? temporary() : ImmutableList.of())
                .build();
        return task.withTaskScope(scope.withWritableRoots(roots), task.asksUser());
    }

    /** The host's temporary folders the host application lists, for a test environment with no {@code TMPDIR} of its own. */
    public static List<Path> temporary()
    {
        ImmutableSet.Builder<Path> roots = ImmutableSet.builder();
        for (String candidate : ImmutableList.of("/tmp", "/var/tmp", System.getProperty("java.io.tmpdir"))) {
            try {
                roots.add(Path.of(candidate).toRealPath());
            }
            catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return roots.build().asList();
    }

    /** The build caches the host application lists under {@code home}. */
    public static List<Path> buildCaches(Path home)
    {
        try {
            ImmutableList.Builder<Path> caches = ImmutableList.builder();
            for (String cache : ImmutableList.of(".m2", ".gradle", ".npm")) {
                caches.add(Files.createDirectories(home.resolve(cache)).toRealPath());
            }
            return caches.build();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}

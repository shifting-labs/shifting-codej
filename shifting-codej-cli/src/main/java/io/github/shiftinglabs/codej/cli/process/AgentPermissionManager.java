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

import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/** Checks a provider's concrete file-write request against the host application's already granted roots. */
public final class AgentPermissionManager
{
    private AgentPermissionManager() {}

    /** Unknown paths and actions are left to the task approval card; shell commands are never inferred from their text. */
    public static boolean allowsFileWrite(Path requested, Path worktree, AgentPermissionScope scope)
    {
        requireNonNull(requested, "requested is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(scope, "scope is null");
        if (!requested.isAbsolute() || scope.access() != SessionAccess.FULL || scope.approveEveryEdit()) {
            return false;
        }
        try {
            Path target = canonicalTarget(requested.normalize());
            Path taskRoot = worktree.toRealPath();
            return target.startsWith(taskRoot) || scope.writableRoots().stream().anyMatch(target::startsWith);
        }
        catch (IOException | SecurityException failure) {
            return false;
        }
    }

    private static Path canonicalTarget(Path target)
            throws IOException
    {
        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("File-write path has no existing ancestor");
        }
        return existing.toRealPath().resolve(existing.relativize(target)).normalize();
    }
}

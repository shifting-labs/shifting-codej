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
package io.github.shiftinglabs.codej.cli.driver;

import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * What a provider session runs with.
 *
 * @param asksUser whether the agent may ask the user a question with choices (Claude's {@code AskUserQuestion}); each question reaches the
 * {@code approvals}, and the user's answers ride back on the allow. The host application decides it: someone must be there to answer
 * @param scope what the session may do: its access and the settings the host application chose for it
 */
public record AgentSessionContext(
        String taskId,
        Path worktree,
        Path runtimeDirectory,
        Path providerDirectory,
        AgentPermissionScope scope,
        Optional<AgentSessionRef> sessionReference,
        AgentApprovalHandler approvals,
        Optional<String> model,
        Optional<String> effort,
        String managedInstructions,
        List<Tool> sessionTools,
        boolean asksUser)
{
    public AgentSessionContext(
            String taskId, Path worktree, Path runtimeDirectory, Path providerDirectory, AgentPermissionPolicy permissionPolicy,
            Optional<AgentSessionRef> sessionReference, AgentApprovalHandler approvals, Optional<String> model, Optional<String> effort,
            SessionAccess access, String managedInstructions)
    {
        this(taskId, worktree, runtimeDirectory, providerDirectory, permissionPolicy, sessionReference, approvals, model, effort,
                access, managedInstructions, ImmutableList.of());
    }

    public AgentSessionContext(
            String taskId, Path worktree, Path runtimeDirectory, Path providerDirectory, AgentPermissionPolicy permissionPolicy,
            Optional<AgentSessionRef> sessionReference, AgentApprovalHandler approvals, Optional<String> model, Optional<String> effort,
            SessionAccess access, String managedInstructions, List<Tool> sessionTools)
    {
        this(taskId, worktree, runtimeDirectory, providerDirectory, new AgentPermissionScope(permissionPolicy, access, false), sessionReference, approvals, model,
                effort, managedInstructions, sessionTools, false);
    }

    public AgentSessionContext
    {
        checkText(taskId, "taskId");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(runtimeDirectory, "runtimeDirectory is null");
        requireNonNull(providerDirectory, "providerDirectory is null");
        requireNonNull(scope, "scope is null");
        SessionAccess access = scope.access();
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(model, "model is null").ifPresent(value -> checkText(value, "model"));
        requireNonNull(effort, "effort is null").ifPresent(value -> checkText(value, "effort"));
        requireNonNull(managedInstructions, "managedInstructions is null");
        sessionTools = ImmutableList.copyOf(sessionTools);
        checkArgument(sessionTools.isEmpty() || access != SessionAccess.TEXT_ONLY, "A text-only session has no tools");
        checkArgument(model.isPresent() || effort.isEmpty(), "effort requires a model");
        checkArgument(!scope.review() || access == SessionAccess.FULL || access == SessionAccess.REVIEW_READ_ONLY, "Only a task's own or borrowed checkout is reviewed");
        checkArgument(!scope.publicationProtected() || !access.restricted(), "Only a full session publishes");
        // Its questions reach the user through the approvals, which only a task's turn and a read-only discussion keep for them.
        checkArgument(!asksUser || access == SessionAccess.FULL || access == SessionAccess.READ_ONLY, "Only a full or read-only session asks the user");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        checkArgument(runtimeDirectory.isAbsolute() && providerDirectory.isAbsolute(), "runtime directories are not absolute");
        checkArgument(providerDirectory.toAbsolutePath().normalize().startsWith(runtimeDirectory.toAbsolutePath().normalize()), "providerDirectory escapes runtimeDirectory");
    }

    /** This context for a task's own turn: what the host application lets it do, and whether the agent may ask the user. */
    public AgentSessionContext withTaskScope(AgentPermissionScope scope, boolean asksUser)
    {
        return new AgentSessionContext(taskId, worktree, runtimeDirectory, providerDirectory, scope, sessionReference, approvals, model, effort,
                managedInstructions, sessionTools, asksUser);
    }

    public AgentPermissionPolicy permissionPolicy()
    {
        return scope.policy();
    }

    public SessionAccess access()
    {
        return scope.access();
    }

    /** Whether the host application runs this task as a review, which reads but never writes. */
    public boolean review()
    {
        return scope.review();
    }

    /** Whether the host application protects this task's publication, so it reaches remotes only through the host application. */
    public boolean publicationProtected()
    {
        return scope.publicationProtected();
    }

    /** Whether this session has no tools at all. */
    public boolean textOnly()
    {
        return scope.access() == SessionAccess.TEXT_ONLY;
    }

    /** Whether this session withholds the capability to change its worktree. */
    public boolean restricted()
    {
        return scope.access().restricted();
    }

    /**
     * Whether the worktree is no task's checkout: a scratch folder inside the runtime directory, or the artifact writer's checkout in
     * {@code <runtime root>/artifacts}, beside the run's directory.
     */
    public boolean scratchWorktree()
    {
        Path folder = worktree.toAbsolutePath().normalize();
        Path runtime = runtimeDirectory.toAbsolutePath().normalize();
        return folder.startsWith(runtime) || folder.startsWith(runtime.resolveSibling("artifacts"));
    }
}

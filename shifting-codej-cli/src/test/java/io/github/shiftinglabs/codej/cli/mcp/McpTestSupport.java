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
package io.github.shiftinglabs.codej.cli.mcp;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.cli.TaskScopes;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionPolicy;
import io.github.shiftinglabs.codej.cli.driver.AgentPermissionScope;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.SessionAccess;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class McpTestSupport
{
    private McpTestSupport() {}

    /** A task session's scope with the settings the host application gives it. */
    static AgentPermissionScope taskScope(AgentPermissionPolicy policy, boolean review)
    {
        return TaskScopes.of(policy, SessionAccess.FULL, review, false);
    }

    static Optional<McpRegistration> registerCodex(PermissionBroker broker, String taskId, Path directory, AgentPermissionPolicy policy, SecretRedactor redactor)
    {
        return registerCodex(broker, taskId, directory, taskScope(policy, false), redactor);
    }

    /** A Codex registration for a task the host application runs as a review, holding the session tools the host application gives it. */
    static Optional<McpRegistration> registerReviewCodex(
            PermissionBroker broker, String taskId, Path directory, AgentPermissionPolicy policy, SecretRedactor redactor, List<Tool> sessionTools)
    {
        return registerCodex(broker, taskId, directory, taskScope(policy, true), redactor, sessionTools);
    }

    static Optional<McpRegistration> registerCodex(PermissionBroker broker, String taskId, Path directory, AgentPermissionScope scope, SecretRedactor redactor)
    {
        return registerCodex(broker, taskId, directory, scope, redactor, ImmutableList.of());
    }

    static Optional<McpRegistration> registerCodex(
            PermissionBroker broker, String taskId, Path directory, AgentPermissionScope scope, SecretRedactor redactor, List<Tool> sessionTools)
    {
        return broker.registerTools(taskId, directory, scope,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, sessionTools);
    }

    static McpRegistration registerClaude(PermissionBroker broker, String taskId, Path directory, AgentPermissionPolicy policy, SecretRedactor redactor)
    {
        return registerClaude(broker, taskId, directory, taskScope(policy, false), redactor);
    }

    /** A Claude registration for a task the host application runs as a review, holding the session tools the host application gives it. */
    static McpRegistration registerReviewClaude(
            PermissionBroker broker, String taskId, Path directory, AgentPermissionPolicy policy, SecretRedactor redactor, List<Tool> sessionTools)
    {
        return registerClaude(broker, taskId, directory, taskScope(policy, true), redactor, sessionTools);
    }

    static McpRegistration registerClaude(PermissionBroker broker, String taskId, Path directory, AgentPermissionScope scope, SecretRedactor redactor)
    {
        return registerClaude(broker, taskId, directory, scope, redactor, ImmutableList.of());
    }

    static McpRegistration registerClaude(
            PermissionBroker broker, String taskId, Path directory, AgentPermissionScope scope, SecretRedactor redactor, List<Tool> sessionTools)
    {
        return broker.register(taskId, directory, directory, directory, scope,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, ImmutableMap.of(), sessionTools);
    }
}

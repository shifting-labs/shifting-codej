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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkOptionalText;
import static io.github.shiftinglabs.codej.cli.driver.Checks.checkText;
import static java.util.Objects.requireNonNull;

public record ProviderApprovalRequest(
        Kind kind,
        String title,
        String detail,
        List<String> requestedPermissions,
        List<Decision> availableDecisions,
        /** The words a task-wide allow covers; present whenever {@link Decision#ALLOW_FOR_TASK} is offered. */
        Optional<String> taskScope,
        /** Whether the action stays inside the provider's sandbox. Requests saved before this flag read as false. */
        boolean sandboxed,
        ProviderAgentEventSource source,
        Instant requestedAt,
        Optional<ProviderApprovalScope> scope,
        /** A command request's command, redacted like {@code detail}, so a handler can judge it without parsing the card's text. */
        Optional<String> command)
{
    /**
     * The scope of a question with choices the agent asks the user. Every lane presents one this way, with the questions in the
     * {@code detail} as this tool's input ({@code {"questions":[{"question","header","multiSelect","options":[{"label","description"}]}]}}),
     * so one card shows it and the answers come back keyed by each question's text.
     */
    public static final String QUESTION_TOOL = "AskUserQuestion";

    public ProviderApprovalRequest
    {
        requireNonNull(kind, "kind is null");
        checkText(title, "title");
        requireNonNull(detail, "detail is null");
        requestedPermissions = ImmutableList.copyOf(requestedPermissions);
        availableDecisions = ImmutableList.copyOf(availableDecisions);
        checkOptionalText(taskScope, "taskScope");
        requireNonNull(source, "source is null");
        requireNonNull(requestedAt, "requestedAt is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(command, "command is null");
        checkArgument(command.isEmpty() || kind == Kind.COMMAND, "only a command request carries a command");
        checkArgument(scope.isEmpty() || (kind == Kind.COMMAND || kind == Kind.TOOL) && !requestedPermissions.contains(QUESTION_TOOL),
                "only a concrete command or tool operation can carry an approval scope");
        checkArgument(requestedPermissions.stream().noneMatch(String::isBlank), "requestedPermissions contains a blank value");
        checkArgument(!availableDecisions.isEmpty(), "availableDecisions is empty");
        checkArgument(availableDecisions.contains(Decision.DENY), "availableDecisions does not contain DENY");
        checkArgument(!availableDecisions.contains(Decision.ALLOW_FOR_TASK) || taskScope.isPresent(), "ALLOW_FOR_TASK is offered without a taskScope");
    }

    public ProviderApprovalRequest(Kind kind, String title, String detail, List<String> requestedPermissions, List<Decision> availableDecisions,
            Optional<String> taskScope, boolean sandboxed, ProviderAgentEventSource source, Instant requestedAt, Optional<ProviderApprovalScope> scope)
    {
        this(kind, title, detail, requestedPermissions, availableDecisions, taskScope, sandboxed, source, requestedAt, scope, Optional.empty());
    }

    public ProviderApprovalRequest(Kind kind, String title, String detail, List<String> requestedPermissions, List<Decision> availableDecisions,
            Optional<String> taskScope, boolean sandboxed, ProviderAgentEventSource source, Instant requestedAt)
    {
        this(kind, title, detail, requestedPermissions, availableDecisions, taskScope, sandboxed, source, requestedAt, Optional.empty());
    }

    /** A request nothing task-wide can cover, such as one from a provider that names no scope. */
    public ProviderApprovalRequest(
            Kind kind,
            String title,
            String detail,
            List<String> requestedPermissions,
            List<Decision> availableDecisions,
            boolean sandboxed,
            ProviderAgentEventSource source,
            Instant requestedAt)
    {
        this(kind, title, detail, requestedPermissions, availableDecisions, Optional.empty(), sandboxed, source, requestedAt);
    }

    public enum Kind
    {
        COMMAND,
        FILE_CHANGE,
        TOOL,
        OTHER
    }

    public enum Decision
    {
        ALLOW_ONCE,
        /** Allows this command and every later one in the task with the same {@code taskScope}. */
        ALLOW_FOR_TASK,
        DENY
    }
}

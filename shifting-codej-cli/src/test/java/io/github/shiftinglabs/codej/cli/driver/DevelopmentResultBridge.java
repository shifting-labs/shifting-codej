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

/** The host application's {@code DevelopmentResultBridge} names and text, as sample tools for the driver tests. */
public final class DevelopmentResultBridge
{
    public static final String TOOL_NAME = "report_development_result";

    public static final String PUBLISH_TOOL_NAME = "create_draft_pull_request";

    public static final String INSTRUCTION = """
            Development result reporting is managed by the host application for this turn. Before your final response,
            the root agent must call report_development_result with readyForDelivery=true only when the
            requested development work and appropriate verification are finished and no user decision is open.
            Report readyForDelivery=false before asking for an unresolved decision; do not perform the action
            awaiting that decision. A request to review before pushing or updating a PR holds publication,
            not authorized local commits of finished work. The reviewer shares this task's working copy and
            can review uncommitted changes; a review request does not require a commit. Follow Auto commit
            and explicit user restrictions independently. Keep readyForDelivery=false while publication
            is on hold so automatic publication cannot start. Missing or invalid reports never authorize delivery. Subagents may
            contribute work but cannot report the root result. Do not supply task, turn, or agent identities.
            An optional message explains the outcome in at most 4000 characters. The user's accepted
            automation settings control delivery; this report cannot change them or select a stopping stage.
            If an explicit user restriction conflicts with enabled automation, report readyForDelivery=false
            and explain which enabled setting conflicts. A started task's automation cannot change, so suggest
            withdrawing the restriction or starting a new task with different settings. Never disregard requests
            such as "do not push" or "keep the PR draft" to satisfy an enabled automation setting.
            Completing a generated commit or draft-publication step does not end the remaining automation.
            Reporting readiness does not itself commit, push, create a PR, or authorize additional work.
            For the host application's generated publication turn, follow its publicPublication flag: check privacy only when
            true; skip the publication privacy inspection when false. The host application resolves destination visibility;
            do not search GitHub, GitLab, gh, browsers or APIs to determine it yourself.
            Use Git/file tools and retained source context for a required check. If a disclosure remains unresolved,
            report readyForDelivery=false and explain without quoting private values. Follow the turn's editing
            permissions; never rewrite shared commits. The host application enforces publication authorization and Git safety,
            without a separate content reviewer.
            During the host application's generated draft-publication step, call create_draft_pull_request with a title
            and body chosen from the actual change and the repository's conventions. Neither carries an
            attribution, co-authorship, or generated-with trailer. For that generated step, the host application owns
            the push and the pull request write; use the tool without duplicating its writes through git, gh or glab.
            The tool refuses calls outside the host application's own publication request. During ordinary development
            turns, discover the host application's current tools before handling publication requests. Prefer a supported
            the host application action; command-based publication is available only when the session's command scope permits it.
            Source-backed tasks must use the host application's publication actions, including for explicit user requests.
            Never rename the task branch through Git commands, even when it is still unpublished; only
            rename_task, when it is offered, proposes a new name. A requested pull-request replacement belongs
            to the host application's replace_task_pull_request action when available.
            """;

    private DevelopmentResultBridge() {}
}

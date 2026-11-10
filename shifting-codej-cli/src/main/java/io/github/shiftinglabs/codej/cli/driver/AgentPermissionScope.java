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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Provider-independent permissions selected for one task session.
 *
 * @param network whether sandboxed commands may use the network, as a build that downloads dependencies does
 * @param webSearch whether the agent may search and fetch the web with its own tools, apart from its commands' network
 * @param noSubagents whether the agent must work alone, starting no subagents
 * @param approveEveryCommand whether every command the agent runs, sandboxed or not, goes to the approval handler first
 * @param fixedSandbox whether the sandbox is fixed when the session starts, so nothing during the session can widen it
 * @param commits whether the agent may commit in its linked Git worktree, which makes that worktree's own Git metadata writable
 * @param readOnlySubagents whether the agent's subagents may only read: they start nothing of their own, call only the tools
 *         marked for subagents, and never ask for approval
 * @param writableRoots folders besides the working directory the agent may write, such as the build caches a build fills
 * @param unsandboxedCommands commands Claude runs outside its sandbox, as {@code mvn *}, for a build tool whose downloads its
 *         network proxy cannot carry; Codex has no such exemption
 * @param unixSockets local sockets sandboxed commands may connect to when they have the network, such as Docker's; Claude only
 * @param standingInstructions the rules the agent works under, which lead its instructions and name its setup
 * @param reviewerContext what Claude's own command reviewer is told about the session, besides its defaults
 * @param reviewerRules what Claude's own command reviewer must refuse, besides its defaults; empty leaves its auto mode off
 * @param reviewSandboxedCommands whether Claude's reviewer checks even sandboxed commands, which otherwise run unchecked
 * @param approveEveryEdit whether every file edit goes to the approval handler, even inside the session's own folders
 * @param sessionDeniedCommands command prefixes the session's own options deny, besides the ones this scope derives
 * @param sessionDeniedReads paths the session's own options deny reading, besides the ones this scope derives
 */
public record AgentPermissionScope(
        AgentPermissionPolicy policy, SessionAccess access, boolean review, boolean publicationProtected, boolean network, boolean webSearch, boolean noSubagents,
        boolean approveEveryCommand, boolean fixedSandbox, boolean commits,
        boolean readOnlySubagents, List<Path> writableRoots, List<String> unsandboxedCommands, List<String> unixSockets, String standingInstructions,
        List<String> reviewerContext, List<String> reviewerRules, boolean reviewSandboxedCommands, boolean approveEveryEdit, List<String> sessionDeniedCommands,
        List<String> sessionDeniedReads)
{
    private static final List<String> LOGIN_FILES = ImmutableList.of("~/.config/gh", "~/.git-credentials", "~/.netrc", "~/.ssh");
    /** Remote writes and credential commands an attended review refuses outright while it reads the network. */
    private static final List<String> REVIEW_REMOTE_WRITES = ImmutableList.of(
            "git push", "git send-email", "git request-pull", "git credential", "gh auth", "gh release", "gh secret", "gh ssh-key", "gh gpg-key",
            "gh pr comment", "gh pr review", "gh pr merge", "gh pr close", "gh pr reopen", "gh pr edit", "gh pr create", "gh pr ready", "gh pr lock", "gh pr unlock",
            "gh issue comment", "gh issue close", "gh issue create", "gh issue edit", "gh issue reopen", "gh issue lock", "gh issue unlock", "gh issue delete",
            "gh repo create", "gh repo delete", "gh repo edit", "gh repo fork", "gh repo sync",
            "gh label create", "gh label delete", "gh label edit", "gh workflow run", "gh workflow enable", "gh workflow disable");
    /** Private review experiments stay uncommitted. */
    private static final List<String> REVIEW_HISTORY_WRITES = Stream.of("commit", "commit-tree", "update-ref", "merge", "rebase", "cherry-pick", "revert", "am", "switch", "pull")
            .map(command -> "git " + command)
            .collect(ImmutableList.toImmutableList());

    public AgentPermissionScope(AgentPermissionPolicy policy, SessionAccess access, boolean review)
    {
        this(policy, access, review, false);
    }

    /** A scope with every setting the session must be given off; the host application turns on the ones a session gets. */
    public AgentPermissionScope(AgentPermissionPolicy policy, SessionAccess access, boolean review, boolean publicationProtected)
    {
        this(policy, access, review, publicationProtected, false, false, false, false, false, false, false, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "",
                ImmutableList.of(), ImmutableList.of(), false, false, ImmutableList.of(), ImmutableList.of());
    }

    public AgentPermissionScope
    {
        requireNonNull(policy, "policy is null");
        requireNonNull(access, "access is null");
        writableRoots = ImmutableList.copyOf(writableRoots);
        unsandboxedCommands = ImmutableList.copyOf(unsandboxedCommands);
        unixSockets = ImmutableList.copyOf(unixSockets);
        requireNonNull(standingInstructions, "standingInstructions is null");
        reviewerContext = ImmutableList.copyOf(reviewerContext);
        reviewerRules = ImmutableList.copyOf(reviewerRules);
        sessionDeniedCommands = ImmutableList.copyOf(sessionDeniedCommands);
        sessionDeniedReads = ImmutableList.copyOf(sessionDeniedReads);
    }

    /** Attended development may use Git and GitHub commands under the shared authorization rules. */
    public boolean githubCommands()
    {
        return remoteReads() && !review;
    }

    /** Attended sessions that own a worktree may read remotes. */
    public boolean remoteReads()
    {
        return policy == AgentPermissionPolicy.AUTO && !access.restricted() && !publicationProtected;
    }

    public AgentPermissionScope withNetwork(boolean network)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withWebSearch(boolean webSearch)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withNoSubagents(boolean noSubagents)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    /**
     * Commands the session refuses outright, by prefix. A deny list must never default open, so the host application derives it
     * for every scope here instead of leaving it to whoever builds one.
     * ponytail: prefixes catch only the plain command form; the host application's review rule at the approval boundary
     * and the providers' own command review cover the rest, such as a gh api request with a write method.
     */
    public List<String> deniedCommands()
    {
        ImmutableList.Builder<String> denied = ImmutableList.builder();
        if (reviewReads()) {
            denied.addAll(REVIEW_REMOTE_WRITES);
        }
        else if (!githubCommands()) {
            denied.add("git credential", "git push", "gh");
        }
        if (review) {
            denied.addAll(REVIEW_HISTORY_WRITES);
        }
        return denied.addAll(sessionDeniedCommands).build();
    }

    /**
     * Paths neither the agent nor its sandboxed commands may read. A session that reads remotes keeps the login files
     * git, gh and ssh need; custom CLI config locations in {@code environment} must not evade the boundary.
     */
    public List<String> deniedReads(Map<String, String> environment)
    {
        ImmutableList.Builder<String> denied = ImmutableList.builder();
        if (!remoteReads()) {
            denied.addAll(LOGIN_FILES);
            Optional.ofNullable(environment.get("XDG_CONFIG_HOME")).filter(value -> !value.isBlank())
                    .map(value -> Path.of(value).resolve("gh").toString()).ifPresent(denied::add);
            Optional.ofNullable(environment.get("GH_CONFIG_DIR")).filter(value -> !value.isBlank()).ifPresent(denied::add);
        }
        return denied.addAll(sessionDeniedReads).build();
    }

    public AgentPermissionScope withApproveEveryCommand(boolean approveEveryCommand)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withFixedSandbox(boolean fixedSandbox)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withCommits(boolean commits)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withReadOnlySubagents(boolean readOnlySubagents)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withUnsandboxedCommands(List<String> unsandboxedCommands)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withUnixSockets(List<String> unixSockets)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withStandingInstructions(String standingInstructions)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    /** Claude's own reviewer: what it is told about the session, what it must refuse, and whether it checks sandboxed commands too. */
    public AgentPermissionScope withReviewer(List<String> reviewerContext, List<String> reviewerRules, boolean reviewSandboxedCommands)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withApproveEveryEdit(boolean approveEveryEdit)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    public AgentPermissionScope withWritableRoots(List<Path> writableRoots)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    /** This scope with the command prefixes and paths the session's own options deny, added to the ones it derives. */
    public AgentPermissionScope withSessionDenials(List<String> sessionDeniedCommands, List<String> sessionDeniedReads)
    {
        return new AgentPermissionScope(policy, access, review, publicationProtected, network, webSearch, noSubagents, approveEveryCommand, fixedSandbox, commits,
                readOnlySubagents, writableRoots, unsandboxedCommands, unixSockets, standingInstructions, reviewerContext, reviewerRules, reviewSandboxedCommands,
                approveEveryEdit, sessionDeniedCommands, sessionDeniedReads);
    }

    /** Private review can read remotes but cannot write to them. */
    public boolean reviewReads()
    {
        return review && remoteReads();
    }

    /** CI repair and cherry-pick normally run without an attending user. */
    public boolean unattended()
    {
        return policy.unattended();
    }
}

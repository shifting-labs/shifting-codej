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
package io.github.shiftinglabs.codej;

import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * How a session runs: where, with how much access, on which model, with which tools, and who answers
 * its requests. Build with {@link #in(Path)}.
 *
 * @param workingDirectory where the agent works; an existing absolute directory
 * @param access what the agent may do there
 * @param network whether commands may use the network
 * @param model a {@link Model#id()}; the agent's default model when empty
 * @param effort a reasoning effort; the model's default when empty
 * @param instructions added to the provider's system prompt for every turn
 * @param tools tools of your own the agent can call, with distinct names
 * @param approvals decides requests beyond {@code access}
 * @param approvalTimeout how long an approval or question may wait before it counts as denied or unanswered
 * @param questions answers the agent's questions; when empty, the agent is told it cannot ask
 * @param writableRoots folders besides the working directory the agent may write, such as the caches a build fills
 * @param deniedCommands command prefixes the agent may never run; they are refused without asking {@code approvals}
 * @param deniedReads files and folders the agent may never read, such as credentials; Claude only, since Codex's sandbox has no
 *         read rules
 * @param webSearch whether the agent may search and fetch the web with its own tools, apart from its commands' network
 * @param commits whether commands may commit, which makes a linked Git worktree's own metadata writable too
 * @param subagents whether the agent may start subagents, and what they may do
 * @param approveEveryCommand whether every command, sandboxed or not, goes to {@code approvals} first
 * @param approveEveryEdit whether every file edit goes to {@code approvals} first, even inside the working directory
 * @param fixedSandbox whether nothing during the session can widen its sandbox
 */
public record SessionOptions(
        Path workingDirectory,
        Access access,
        boolean network,
        Optional<String> model,
        Optional<String> effort,
        Optional<String> instructions,
        List<Tool> tools,
        ApprovalHandler approvals,
        Duration approvalTimeout,
        Optional<QuestionHandler> questions,
        List<Path> writableRoots,
        List<String> deniedCommands,
        List<Path> deniedReads,
        boolean webSearch,
        boolean commits,
        Subagents subagents,
        boolean approveEveryCommand,
        boolean approveEveryEdit,
        boolean fixedSandbox)
{
    /**
     * Creates options; copies {@code tools}.
     *
     * @throws IllegalArgumentException when {@code workingDirectory} or a path in {@code writableRoots} or {@code deniedReads} is
     *         relative, two tools share a name, a denied command is blank, or {@code approvalTimeout} is not positive
     */
    public SessionOptions
    {
        requireNonNull(workingDirectory, "workingDirectory is null");
        checkArgument(workingDirectory.isAbsolute(), "workingDirectory is not absolute: %s", workingDirectory);
        requireNonNull(access, "access is null");
        requireNonNull(model, "model is null");
        requireNonNull(effort, "effort is null");
        requireNonNull(instructions, "instructions is null");
        tools = ImmutableList.copyOf(tools);
        checkArgument(tools.stream().map(Tool::name).distinct().count() == tools.size(), "Tool names must differ: %s", tools.stream().map(Tool::name).collect(toImmutableList()));
        requireNonNull(approvals, "approvals is null");
        checkArgument(approvalTimeout.isPositive(), "approvalTimeout is not positive: %s", approvalTimeout);
        requireNonNull(questions, "questions is null");
        writableRoots = ImmutableList.copyOf(writableRoots);
        writableRoots.forEach(root -> checkArgument(root.isAbsolute(), "A writable root is not absolute: %s", root));
        deniedCommands = ImmutableList.copyOf(deniedCommands);
        checkArgument(deniedCommands.stream().noneMatch(String::isBlank), "A denied command is blank");
        deniedReads = ImmutableList.copyOf(deniedReads);
        deniedReads.forEach(path -> checkArgument(path.isAbsolute(), "A denied path is not absolute: %s", path));
        requireNonNull(subagents, "subagents is null");
    }

    /** Whether a session's agent may start subagents. */
    public enum Subagents
    {
        /** It may, and they may do what the session may. */
        ALLOWED,
        /** It may, but they only read: they start nothing of their own, call only tools marked {@link Tool#forSubagents()}, and never ask for approval. */
        READ_ONLY,
        /** It works alone. */
        NONE
    }

    /**
     * Starts options for a session in {@code workingDirectory}. The defaults are the safe ones:
     * {@link Access#READ_ONLY}, no network, every approval denied, a five-minute approval timeout,
     * and no questions.
     *
     * @param workingDirectory where the agent works; made absolute
     * @return a new builder
     */
    public static Builder in(Path workingDirectory)
    {
        return new Builder(workingDirectory.toAbsolutePath());
    }

    /** Builds {@link SessionOptions}. Not safe to share between threads. */
    public static final class Builder
    {
        private final Path workingDirectory;
        private Access access = Access.READ_ONLY;
        private boolean network;
        private Optional<String> model = Optional.empty();
        private Optional<String> effort = Optional.empty();
        private Optional<String> instructions = Optional.empty();
        private final ImmutableList.Builder<Tool> tools = ImmutableList.builder();
        private ApprovalHandler approvals = ApprovalHandler.DENY_ALL;
        private Duration approvalTimeout = Duration.ofMinutes(5);
        private Optional<QuestionHandler> questions = Optional.empty();
        private final ImmutableList.Builder<Path> writableRoots = ImmutableList.builder();
        private final ImmutableList.Builder<String> deniedCommands = ImmutableList.builder();
        private final ImmutableList.Builder<Path> deniedReads = ImmutableList.builder();
        private boolean webSearch;
        private boolean commits;
        private Subagents subagents = Subagents.ALLOWED;
        private boolean approveEveryCommand;
        private boolean approveEveryEdit;
        private boolean fixedSandbox;

        private Builder(Path workingDirectory)
        {
            this.workingDirectory = workingDirectory;
        }

        /**
         * Sets what the agent may do in the working directory.
         *
         * @param access the access level
         * @return this builder
         */
        public Builder access(Access access)
        {
            this.access = requireNonNull(access, "access is null");
            return this;
        }

        /**
         * Lets the agent's commands use the network.
         *
         * @return this builder
         */
        public Builder allowNetwork()
        {
            this.network = true;
            return this;
        }

        /**
         * Sets the session's model.
         *
         * @param model a {@link Model#id()} from {@link Agent#models()}
         * @return this builder
         */
        public Builder model(String model)
        {
            this.model = Optional.of(model);
            return this;
        }

        /**
         * Sets the session's reasoning effort.
         *
         * @param effort one of the model's {@link Model#efforts()}
         * @return this builder
         */
        public Builder effort(String effort)
        {
            this.effort = Optional.of(effort);
            return this;
        }

        /**
         * Adds your own instructions to the provider's system prompt for every turn.
         *
         * @param instructions the instructions
         * @return this builder
         */
        public Builder instructions(String instructions)
        {
            this.instructions = Optional.of(instructions);
            return this;
        }

        /**
         * Gives the agent one more tool.
         *
         * @param tool the tool; its name must differ from the other tools'
         * @return this builder
         */
        public Builder tool(Tool tool)
        {
            tools.add(tool);
            return this;
        }

        /**
         * Sets who decides requests beyond the session's access. Default: {@link ApprovalHandler#DENY_ALL}.
         *
         * @param approvals the handler
         * @return this builder
         */
        public Builder approvals(ApprovalHandler approvals)
        {
            this.approvals = requireNonNull(approvals, "approvals is null");
            return this;
        }

        /**
         * Sets how long an approval or a question may wait for an answer. Default: five minutes.
         *
         * @param timeout the wait; positive
         * @return this builder
         */
        public Builder approvalTimeout(Duration timeout)
        {
            this.approvalTimeout = requireNonNull(timeout, "timeout is null");
            return this;
        }

        /**
         * Lets the agent ask the user questions, answered by {@code questions}.
         *
         * @param questions the handler
         * @return this builder
         */
        public Builder questions(QuestionHandler questions)
        {
            this.questions = Optional.of(questions);
            return this;
        }

        /**
         * Lets the agent write one more folder besides the working directory.
         *
         * @param folder an absolute path
         * @return this builder
         */
        public Builder writableRoot(Path folder)
        {
            writableRoots.add(folder);
            return this;
        }

        /**
         * Refuses every command that starts with {@code prefix}, without asking the approval handler.
         *
         * @param prefix such as {@code git push}
         * @return this builder
         */
        public Builder denyCommand(String prefix)
        {
            deniedCommands.add(prefix);
            return this;
        }

        /**
         * Keeps the agent from reading a file or folder. Claude only: Codex's sandbox has no read rules.
         *
         * @param path an absolute path
         * @return this builder
         */
        public Builder denyRead(Path path)
        {
            deniedReads.add(path);
            return this;
        }

        /**
         * Lets the agent search and fetch the web with its own tools.
         *
         * @return this builder
         */
        public Builder allowWebSearch()
        {
            this.webSearch = true;
            return this;
        }

        /**
         * Lets commands commit, including in a linked Git worktree, whose metadata lives outside the working directory.
         *
         * @return this builder
         */
        public Builder allowCommits()
        {
            this.commits = true;
            return this;
        }

        /**
         * Keeps the agent from starting subagents.
         *
         * @return this builder
         */
        public Builder noSubagents()
        {
            this.subagents = Subagents.NONE;
            return this;
        }

        /**
         * Lets the agent start subagents that only read. When the provider cannot hold them to that, the agent starts none.
         *
         * @return this builder
         */
        public Builder readOnlySubagents()
        {
            this.subagents = Subagents.READ_ONLY;
            return this;
        }

        /**
         * Sends every command to the approval handler first, sandboxed or not.
         *
         * @return this builder
         */
        public Builder approveEveryCommand()
        {
            this.approveEveryCommand = true;
            return this;
        }

        /**
         * Sends every file edit to the approval handler first, even inside the working directory.
         *
         * @return this builder
         */
        public Builder approveEveryEdit()
        {
            this.approveEveryEdit = true;
            return this;
        }

        /**
         * Fixes the sandbox when the session starts, so nothing during it can widen the sandbox.
         *
         * @return this builder
         */
        public Builder fixedSandbox()
        {
            this.fixedSandbox = true;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException when two tools share a name, a path is relative, a denied command is blank, or the approval
         *         timeout is not positive
         */
        public SessionOptions build()
        {
            return new SessionOptions(workingDirectory, access, network, model, effort, instructions, tools.build(), approvals, approvalTimeout, questions,
                    writableRoots.build(), deniedCommands.build(), deniedReads.build(), webSearch, commits, subagents, approveEveryCommand, approveEveryEdit,
                    fixedSandbox);
        }
    }
}

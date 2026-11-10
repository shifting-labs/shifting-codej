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
import java.util.OptionalInt;

/**
 * Something the agent did during a turn, delivered to the turn's listener as it happens.
 *
 * <p>Events about one item, such as a message or a command, share its {@code id}: a command arrives
 * as {@link Activity#STARTED}, maybe {@link Activity#PROGRESS}, then {@link Activity#COMPLETED} or
 * {@link Activity#FAILED}. Providers keep adding kinds of work, so new event types may come in a minor
 * release: end a {@code switch} over events with {@code default}.
 */
public sealed interface AgentEvent
{
    /**
     * The agent that did it.
     *
     * @return the root agent or a subagent
     */
    Source source();

    /**
     * The provider took the turn. It is a turn's first event; a turn that ends without it never reached
     * the provider, or may not have, as its {@link TurnResult.Failed} says.
     *
     * @param source the root agent
     */
    record Started(Source source)
            implements AgentEvent {}

    /**
     * A piece of a message being written. Append the deltas that share an {@code id}; the whole text
     * follows as a {@link Message}.
     *
     * @param source the agent writing
     * @param id the message's id
     * @param kind what the message is for
     * @param delta the next piece of text
     */
    record MessageDelta(Source source, String id, MessageKind kind, String delta)
            implements AgentEvent {}

    /**
     * A finished message.
     *
     * @param source the agent that wrote it
     * @param id the message's id, as on its deltas
     * @param kind what the message is for
     * @param text the whole message
     */
    record Message(Source source, String id, MessageKind kind, String text)
            implements AgentEvent {}

    /**
     * The agent's current plan, replacing any earlier one in this turn.
     *
     * @param source the agent planning
     * @param steps the plan's steps, in order
     */
    record Plan(Source source, List<Step> steps)
            implements AgentEvent
    {
        /** Creates a plan event; copies {@code steps}. */
        public Plan
        {
            steps = ImmutableList.copyOf(steps);
        }
    }

    /**
     * A shell command. Each event carries everything known about it so far.
     *
     * @param source the agent running it
     * @param id the command's id across its events
     * @param command the command line
     * @param activity where the command is
     * @param output what the command has printed so far
     * @param outputTruncated whether {@code output} leaves out part of what it printed
     * @param exitCode its exit code, once it has ended
     * @param workingDirectory where it runs, when the provider says
     * @param actions what it does, when the provider can tell, such as reading one file
     * @param description what it is for, in the agent's words
     * @param background whether the agent left it running in the background, as for a server, and went on
     * @param duration how long it ran, once it has ended
     */
    record Command(
            Source source,
            String id,
            String command,
            Activity activity,
            String output,
            boolean outputTruncated,
            OptionalInt exitCode,
            Optional<Path> workingDirectory,
            List<Action> actions,
            Optional<String> description,
            boolean background,
            Optional<Duration> duration)
            implements AgentEvent
    {
        /** Creates a command event; copies {@code actions}. */
        public Command
        {
            actions = ImmutableList.copyOf(actions);
        }
    }

    /**
     * Something a {@link Command} does, as the provider reads the command line.
     *
     * @param kind what kind of action it is
     * @param name the provider's short name for it, such as the file it reads
     * @param path the file or folder it reads, lists or searches, relative to the working directory
     * @param query what it searches for
     */
    record Action(ActionKind kind, Optional<String> name, Optional<Path> path, Optional<String> query) {}

    /**
     * A file the agent added, edited, deleted or renamed.
     *
     * @param source the agent that changed it
     * @param id the change's id
     * @param path the file, relative to the working directory
     * @param change what happened to it
     * @param diff a unified diff, when the provider gives one
     */
    record FileChange(Source source, String id, Path path, Change change, Optional<String> diff)
            implements AgentEvent {}

    /**
     * A call to one of the session's {@link Tool}s or to a provider tool, such as web search.
     *
     * @param source the agent calling it
     * @param id the call's id across its events
     * @param tool the tool's name
     * @param activity where the call is
     * @param summary what the call was given or found, in the provider's words
     * @param description a short line saying what the call did, such as the file it read
     * @param output what one of the session's own tools returned, once the call has completed
     */
    record ToolCall(Source source, String id, String tool, Activity activity, Optional<String> summary, Optional<String> description, Optional<String> output)
            implements AgentEvent {}

    /**
     * The tokens this turn has used so far.
     *
     * @param source the agent whose use this is
     * @param usage the running total
     */
    record UsageUpdate(Source source, Usage usage)
            implements AgentEvent {}

    /**
     * Progress worth showing that is not an item, such as a compacted context or a retried request.
     *
     * @param source the agent it concerns
     * @param kind what happened
     * @param text what happened, to show the user
     */
    record Notice(Source source, NoticeKind kind, String text)
            implements AgentEvent {}

    /**
     * A subagent started or ended. Its own events carry the same {@link Source}.
     *
     * @param source the subagent
     * @param activity {@link Activity#STARTED}, then {@link Activity#COMPLETED} or {@link Activity#FAILED}
     */
    record Subagent(Source source, Activity activity)
            implements AgentEvent {}

    /**
     * Brackets events read back from the conversation's saved history rather than seen as they
     * happened, as when a subagent's work is recovered after the session resumed.
     *
     * @param source the agent whose events are read back
     * @param activity {@link Activity#STARTED} before the first such event, {@link Activity#COMPLETED} after the last
     */
    record Replay(Source source, Activity activity)
            implements AgentEvent {}

    /**
     * A problem the provider reported. The turn may go on; how it ended is in its {@link TurnResult}.
     *
     * @param source the agent it concerns
     * @param message the provider's explanation
     * @param retryable whether the provider says trying again may help
     */
    record Error(Source source, String message, boolean retryable)
            implements AgentEvent {}

    /** What a message is for. */
    enum MessageKind
    {
        /** Written to the user. */
        ANSWER,
        /** Notes on progress while the agent works. */
        COMMENTARY,
        /** A summary of the model's reasoning. */
        REASONING_SUMMARY,
        /** A plan written as text, when the agent does not send a {@link Plan}. */
        PLAN,
        /** What a subagent was asked by the agent that started it. */
        PROMPT
    }

    /** What a {@link Notice} reports. */
    enum NoticeKind
    {
        /** The provider is trying a failed request again. */
        RETRYING,
        /** The account's rate limit is slowing the agent down. */
        RATE_LIMITED,
        /** The provider summarized the conversation to fit its context, so earlier input may no longer be in it word for word. */
        CONTEXT_COMPACTED
    }

    /** Where a command or a tool call is. */
    enum Activity
    {
        /** It began. */
        STARTED,
        /** It is running and has more to report, such as output. */
        PROGRESS,
        /** It ended successfully. */
        COMPLETED,
        /** It ended with an error. */
        FAILED
    }

    /** What kind of thing a command does. */
    enum ActionKind
    {
        /** Reads a file. */
        READ,
        /** Lists files. */
        LIST_FILES,
        /** Searches files. */
        SEARCH,
        /** Anything else. */
        OTHER
    }

    /** What happened to a file. */
    enum Change
    {
        /** The file is new. */
        ADDED,
        /** Its content changed. */
        MODIFIED,
        /** It was removed. */
        DELETED,
        /** It moved to {@link FileChange#path()}. */
        RENAMED
    }

    /**
     * One step of a {@link Plan}.
     *
     * @param text what the step does
     * @param status how far it is
     */
    record Step(String text, StepStatus status) {}

    /** How far a plan step is. */
    enum StepStatus
    {
        /** Not started. */
        PENDING,
        /** Being worked on. */
        IN_PROGRESS,
        /** Done. */
        COMPLETED
    }
}

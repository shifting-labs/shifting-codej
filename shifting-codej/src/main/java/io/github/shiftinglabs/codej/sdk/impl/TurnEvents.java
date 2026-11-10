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
package io.github.shiftinglabs.codej.sdk.impl;

import com.google.common.collect.ImmutableList;
import io.github.shiftinglabs.codej.AgentEvent;
import io.github.shiftinglabs.codej.Source;
import io.github.shiftinglabs.codej.Usage;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEvent;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import io.github.shiftinglabs.codej.cli.driver.ProviderUsageSample;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Turns one turn's driver events into API events, keeping what the API reports as totals: command output so far and tokens used. */
final class TurnEvents
{
    /** Keeps a command's reported output bounded however much it prints. */
    private static final int MAX_OUTPUT_CHARACTERS = 1024 * 1024;

    private final Map<String, KnownCommand> commands = new HashMap<>();
    private final Map<String, StringBuilder> outputs = new HashMap<>();
    private final Set<String> truncated = new HashSet<>();
    private final Map<Source, Usage> usage = new HashMap<>();
    private final Function<BigDecimal, Optional<BigDecimal>> conversationCost;

    /**
     * @param conversationCost this turn's part of a cost the provider counts across the whole conversation
     */
    TurnEvents(Function<BigDecimal, Optional<BigDecimal>> conversationCost)
    {
        this.conversationCost = requireNonNull(conversationCost, "conversationCost is null");
    }

    Optional<AgentEvent> map(ProviderAgentEvent event)
    {
        Source source = source(event.source());
        return switch (event) {
            // Codex marks a commentary item whole with an event id while it stays partial; only a piece without one is part of a stream.
            case ProviderAgentEvent.Message message -> messageKind(message.messageKind(), source).map(kind -> message.phase() == ProviderAgentEvent.MessagePhase.PARTIAL
                    && message.providerEventId().isEmpty()
                    ? new AgentEvent.MessageDelta(source, id(event), kind, message.text())
                    : new AgentEvent.Message(source, id(event), kind, message.text()));
            case ProviderAgentEvent.Plan plan -> Optional.of(new AgentEvent.Plan(source, plan.items().stream()
                    .map(item -> new AgentEvent.Step(item.text(), switch (item.state()) {
                        case PENDING -> AgentEvent.StepStatus.PENDING;
                        case IN_PROGRESS -> AgentEvent.StepStatus.IN_PROGRESS;
                        case COMPLETED -> AgentEvent.StepStatus.COMPLETED;
                    }))
                    .collect(toImmutableList())));
            // Codex reports a compaction's start too; it has happened only once it completes.
            case ProviderAgentEvent.Tool tool when tool.name().equals(ProviderAgentEvent.Tool.CONTEXT_COMPACTION) -> Optional.<AgentEvent>of(
                    new AgentEvent.Notice(source, AgentEvent.NoticeKind.CONTEXT_COMPACTED, tool.summary().isBlank() ? tool.name() : tool.summary()))
                    .filter(_ -> tool.state() == ProviderAgentEvent.ActivityState.COMPLETED);
            case ProviderAgentEvent.Tool tool -> Optional.of(new AgentEvent.ToolCall(source, id(event), tool.name(), activity(tool.state()),
                    Optional.of(tool.summary()).filter(text -> !text.isBlank()), tool.description(), tool.output().map(ProviderAgentEvent.ToolOutput::text)));
            case ProviderAgentEvent.Command command -> Optional.of(command(source, id(event), command));
            case ProviderAgentEvent.FileChange change -> Optional.of(new AgentEvent.FileChange(source, id(event), Path.of(change.relativePath()), switch (change.change()) {
                case ADDED -> AgentEvent.Change.ADDED;
                case MODIFIED -> AgentEvent.Change.MODIFIED;
                case DELETED -> AgentEvent.Change.DELETED;
                case RENAMED -> AgentEvent.Change.RENAMED;
            }, change.diff()));
            case ProviderAgentEvent.Usage sample -> usage(source, sample.sample()).map(total -> new AgentEvent.UsageUpdate(source, total));
            case ProviderAgentEvent.Lifecycle lifecycle -> switch (lifecycle.phase()) {
                case RETRYING -> Optional.<AgentEvent>of(new AgentEvent.Notice(source, AgentEvent.NoticeKind.RETRYING, lifecycle.message()));
                case RATE_LIMITED -> Optional.of(new AgentEvent.Notice(source, AgentEvent.NoticeKind.RATE_LIMITED, lifecycle.message()));
                case AGENT_STARTED -> Optional.of(new AgentEvent.Subagent(source, AgentEvent.Activity.STARTED));
                case AGENT_COMPLETED -> Optional.of(new AgentEvent.Subagent(source, AgentEvent.Activity.COMPLETED));
                case AGENT_FAILED -> Optional.of(new AgentEvent.Subagent(source, AgentEvent.Activity.FAILED));
                case BACKFILL_STARTED -> Optional.of(new AgentEvent.Replay(source, AgentEvent.Activity.STARTED));
                case BACKFILL_COMPLETED -> Optional.of(new AgentEvent.Replay(source, AgentEvent.Activity.COMPLETED));
                // The turn's own start and end are the caller's: it started the turn, and its result says how it ended.
                case SESSION_OPENED, TURN_STARTED, TURN_COMPLETED, INTERRUPTED -> Optional.empty();
            };
            case ProviderAgentEvent.Error error -> Optional.of(new AgentEvent.Error(source, error.message(), error.retryable()));
        };
    }

    /** The root agent's tokens for the whole turn. */
    Usage rootUsage()
    {
        return usage.getOrDefault(Source.root(), Usage.NONE);
    }

    private AgentEvent command(Source source, String id, ProviderAgentEvent.Command command)
    {
        KnownCommand known = commands.getOrDefault(id, KnownCommand.NOTHING).merge(command);
        commands.put(id, known);
        StringBuilder output = outputs.computeIfAbsent(id, _ -> new StringBuilder());
        command.output().ifPresent(text -> {
            // A finished command reports its whole output; progress reports carry the next piece.
            if (command.state() != ProviderAgentEvent.ActivityState.PROGRESS) {
                output.setLength(0);
                truncated.remove(id);
            }
            int room = Math.max(0, MAX_OUTPUT_CHARACTERS - output.length());
            if (text.length() > room) {
                truncated.add(id);
            }
            output.append(text, 0, Math.min(text.length(), room));
        });
        if (command.truncated()) {
            truncated.add(id);
        }
        return new AgentEvent.Command(source, id, known.command(), activity(command.state()), output.toString(), truncated.contains(id),
                command.exitCode().map(OptionalInt::of).orElse(OptionalInt.empty()), known.workingDirectory(), known.actions(), known.description(), known.background(),
                command.duration());
    }

    /** What a command's events have said so far; a later event may leave out what an earlier one told. */
    private record KnownCommand(String command, Optional<Path> workingDirectory, List<AgentEvent.Action> actions, Optional<String> description, boolean background)
    {
        static final KnownCommand NOTHING = new KnownCommand("", Optional.empty(), ImmutableList.of(), Optional.empty(), false);

        KnownCommand merge(ProviderAgentEvent.Command event)
        {
            return new KnownCommand(
                    event.argv().isEmpty() ? command : String.join(" ", event.argv()),
                    event.cwd().map(Path::of).or(() -> workingDirectory),
                    event.actions().isEmpty() ? actions : event.actions().stream().map(TurnEvents::action).collect(toImmutableList()),
                    event.description().or(() -> description),
                    background || event.background());
        }
    }

    private static AgentEvent.Action action(ProviderAgentEvent.CommandAction action)
    {
        AgentEvent.ActionKind kind = switch (action.kind()) {
            case READ -> AgentEvent.ActionKind.READ;
            case LIST_FILES -> AgentEvent.ActionKind.LIST_FILES;
            case SEARCH -> AgentEvent.ActionKind.SEARCH;
            case OTHER -> AgentEvent.ActionKind.OTHER;
        };
        return new AgentEvent.Action(kind, action.name(), action.path().map(Path::of), action.query());
    }

    private Optional<Usage> usage(Source source, ProviderUsageSample sample)
    {
        Usage before = usage.getOrDefault(source, Usage.NONE);
        Usage total = switch (sample.semantics()) {
            case CUMULATIVE_FOR_INVOCATION -> new Usage(sample.inputTokens(), sample.cachedInputTokens(), sample.outputTokens(), sample.cost());
            case DELTA, FINAL_FOR_INVOCATION -> plus(before, sample, sample.cost());
            // The tokens are this report's own, while the cost runs across the whole conversation.
            case FINAL_WITH_SESSION_COST -> plus(before, sample, sample.cost().flatMap(conversationCost));
            // ponytail: no driver reports session-wide totals; one that does needs the session's count before this turn.
            case CUMULATIVE -> before;
        };
        usage.put(source, total);
        return Optional.of(total).filter(value -> !value.equals(before));
    }

    private static Usage plus(Usage before, ProviderUsageSample sample, Optional<BigDecimal> cost)
    {
        return new Usage(
                before.inputTokens() + sample.inputTokens(),
                before.cachedInputTokens() + sample.cachedInputTokens(),
                before.outputTokens() + sample.outputTokens(),
                cost.map(more -> before.cost().orElse(BigDecimal.ZERO).add(more)).or(before::cost));
    }

    private static Optional<AgentEvent.MessageKind> messageKind(ProviderAgentEvent.MessageKind kind, Source source)
    {
        return switch (kind) {
            case ANSWER -> Optional.of(AgentEvent.MessageKind.ANSWER);
            case COMMENTARY -> Optional.of(AgentEvent.MessageKind.COMMENTARY);
            case REASONING_SUMMARY -> Optional.of(AgentEvent.MessageKind.REASONING_SUMMARY);
            case PLAN_TEXT -> Optional.of(AgentEvent.MessageKind.PLAN);
            // The root agent's prompt is the caller's own input.
            case PROMPT -> Optional.of(AgentEvent.MessageKind.PROMPT).filter(_ -> !source.isRoot());
        };
    }

    private static AgentEvent.Activity activity(ProviderAgentEvent.ActivityState state)
    {
        return switch (state) {
            case STARTED -> AgentEvent.Activity.STARTED;
            case PROGRESS -> AgentEvent.Activity.PROGRESS;
            case COMPLETED -> AgentEvent.Activity.COMPLETED;
            case FAILED -> AgentEvent.Activity.FAILED;
        };
    }

    static Source source(ProviderAgentEventSource source)
    {
        if (source.parentProviderThreadId().isEmpty()) {
            return Source.root();
        }
        return new Source(source.providerThreadId(), source.parentProviderThreadId(), source.logicalAgentPath());
    }

    private static String id(ProviderAgentEvent event)
    {
        return event.source().providerItemId().or(event::providerEventId).orElseGet(() -> UUID.randomUUID().toString());
    }
}

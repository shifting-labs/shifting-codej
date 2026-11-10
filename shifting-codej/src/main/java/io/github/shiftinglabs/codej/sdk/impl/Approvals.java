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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Comparators;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.Approval;
import io.github.shiftinglabs.codej.ApprovalHandler;
import io.github.shiftinglabs.codej.Decision;
import io.github.shiftinglabs.codej.Question;
import io.github.shiftinglabs.codej.QuestionHandler;
import io.github.shiftinglabs.codej.Source;
import io.github.shiftinglabs.codej.cli.driver.AgentApprovalHandler;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalScope;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Asks a session's {@link ApprovalHandler} about each driver request, and its {@link QuestionHandler} each question, on the callback
 * executor. No answer in time, or any failure, denies the request or leaves the question unanswered.
 */
final class Approvals
        implements AgentApprovalHandler
{
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApprovalHandler handler;
    private final Optional<QuestionHandler> questions;
    private final Duration timeout;
    private final Path workingDirectory;
    private final Executor callbacks;
    private volatile boolean closed;

    Approvals(ApprovalHandler handler, Optional<QuestionHandler> questions, Duration timeout, Path workingDirectory, Executor callbacks)
    {
        this.handler = requireNonNull(handler, "handler is null");
        this.questions = requireNonNull(questions, "questions is null");
        this.timeout = requireNonNull(timeout, "timeout is null");
        this.workingDirectory = requireNonNull(workingDirectory, "workingDirectory is null");
        this.callbacks = requireNonNull(callbacks, "callbacks is null");
    }

    @Override
    public CompletionStage<ProviderApprovalDecision> request(ProviderApprovalRequest request, Duration driverTimeout)
    {
        if (closed) {
            // A closed session's turns are over, so a late request from its process asks nobody.
            return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        }
        // The driver's own wait bounds the session's: past it, the provider stops listening for this answer.
        Duration wait = Comparators.min(timeout, driverTimeout);
        if (request.requestedPermissions().contains(ProviderApprovalRequest.QUESTION_TOOL)) {
            Optional<Question> question = question(request);
            if (questions.isEmpty() || question.isEmpty()) {
                // Without a handler the agent was told it cannot ask; a question this library cannot read goes unanswered.
                return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
            }
            QuestionHandler answering = questions.orElseThrow();
            return ask(() -> answering.answer(question.orElseThrow()), wait, answers -> answers(question.orElseThrow(), answers), ProviderApprovalDecision::deny);
        }
        Approval approval = approval(request);
        return ask(() -> handler.decide(approval), wait, decision -> decision(approval, decision), ProviderApprovalDecision::deny);
    }

    /** The session closed: later requests are denied without asking. */
    void close()
    {
        closed = true;
    }

    /**
     * Asks the handler on the callback executor, and answers the driver with what it says, or with {@code unanswered} when it fails or
     * the wait runs out. A request that ends without the handler's answer, by the wait or by the driver withdrawing it, cancels the
     * handler's stage.
     */
    private <T> CompletableFuture<ProviderApprovalDecision> ask(Supplier<CompletionStage<T>> handler, Duration wait, Function<T, ProviderApprovalDecision> answered,
            Supplier<ProviderApprovalDecision> unanswered)
    {
        CompletableFuture<ProviderApprovalDecision> answer = new CompletableFuture<>();
        callbacks.execute(() -> {
            CompletableFuture<T> stage;
            try {
                stage = handler.get().toCompletableFuture();
            }
            catch (RuntimeException failure) {
                answer.complete(unanswered.get());
                return;
            }
            stage.whenComplete((value, failure) -> {
                try {
                    answer.complete(failure == null ? answered.apply(value) : unanswered.get());
                }
                catch (RuntimeException broken) {
                    // Such as a stage completed with null.
                    answer.complete(unanswered.get());
                }
            });
            answer.whenComplete((_, _) -> stage.cancel(false));
        });
        return answer.completeOnTimeout(unanswered.get(), wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Anything but an allowing decision the provider offered denies, and a denial's reason reaches the agent. */
    private static ProviderApprovalDecision decision(Approval approval, Decision decision)
    {
        if (decision.kind() != Decision.Kind.DENY && approval.choices().contains(decision.kind())) {
            return ProviderApprovalDecision.allow(Optional.empty());
        }
        return decision.reason().map(ProviderApprovalDecision::deny).orElseGet(ProviderApprovalDecision::deny);
    }

    private Approval approval(ProviderApprovalRequest request)
    {
        Source source = TurnEvents.source(request.source());
        String reason = request.title();
        Set<Decision.Kind> choices = request.availableDecisions().stream()
                .map(decision -> switch (decision) {
                    case ALLOW_ONCE -> Decision.Kind.ALLOW_ONCE;
                    // ponytail: no driver offers a task-wide grant yet; one that does needs the session to remember what it allowed.
                    case ALLOW_FOR_TASK -> Decision.Kind.ALLOW_ONCE;
                    case DENY -> Decision.Kind.DENY;
                })
                .collect(toImmutableSet());
        return switch (request.kind()) {
            case COMMAND -> new Approval.Command(source, reason, choices, request.command().orElse(request.detail()), workingDirectory, request.sandboxed(),
                    request.scope().map(ProviderApprovalScope::hosts).orElse(ImmutableList.of()));
            case FILE_CHANGE -> new Approval.FileChange(source, reason, choices, filePath(request.detail()).map(ImmutableList::of).orElse(ImmutableList.of()));
            case TOOL -> new Approval.ToolUse(source, reason, choices, tool(request.requestedPermissions()), request.detail());
            case OTHER -> new Approval.Other(source, reason, choices, request.detail());
        };
    }

    /** Both providers ask with Claude's {@code AskUserQuestion} input, whose questions are told apart by their text. */
    private static Optional<Question> question(ProviderApprovalRequest request)
    {
        try {
            ImmutableList.Builder<Question.Item> items = ImmutableList.builder();
            for (JsonNode asked : JSON.readTree(request.detail()).path("questions")) {
                ImmutableList.Builder<Question.Option> options = ImmutableList.builder();
                asked.path("options").forEach(option -> options.add(new Question.Option(option.path("label").asText(""), option.path("description").asText(""))));
                String text = asked.path("question").asText("");
                items.add(new Question.Item(text, asked.path("header").asText(""), text, options.build(), asked.path("multiSelect").asBoolean(false)));
            }
            List<Question.Item> built = items.build();
            return built.isEmpty() ? Optional.empty() : Optional.of(new Question(TurnEvents.source(request.source()), built));
        }
        catch (JsonProcessingException failure) {
            return Optional.empty();
        }
    }

    private static ProviderApprovalDecision answers(Question question, Map<String, List<String>> answers)
    {
        ImmutableMap.Builder<String, String> answered = ImmutableMap.builder();
        for (Question.Item item : question.items()) {
            Optional.ofNullable(answers.get(item.id()))
                    .map(chosen -> String.join(", ", chosen))
                    .filter(answer -> !answer.isBlank())
                    .ifPresent(answer -> answered.put(item.id(), answer));
        }
        Map<String, String> built = answered.buildKeepingLast();
        return built.isEmpty() ? ProviderApprovalDecision.deny() : ProviderApprovalDecision.allow(Optional.empty(), built);
    }

    private static String tool(List<String> requested)
    {
        return requested.isEmpty() ? "" : requested.getFirst();
    }

    /** Claude asks to edit or write with its tool's input, which names the file. */
    private static Optional<Path> filePath(String detail)
    {
        try {
            JsonNode input = JSON.readTree(detail);
            return Optional.ofNullable(input).map(node -> node.path("file_path")).filter(JsonNode::isTextual).map(JsonNode::asText).map(Path::of);
        }
        catch (JsonProcessingException failure) {
            return Optional.empty();
        }
    }
}

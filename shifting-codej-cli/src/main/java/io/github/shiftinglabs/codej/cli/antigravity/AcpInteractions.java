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
package io.github.shiftinglabs.codej.cli.antigravity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.github.shiftinglabs.codej.Question;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.BooleanValue;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ElicitationParams;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ElicitationReply;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.FormAction;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.FormChoice;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.FormField;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.FormMode;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.FormSchema;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.FormValue;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HostReply;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.HostRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.Outcome;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionOption;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionOptionKind;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionOutcome;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionParams;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionReply;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.PermissionRequest;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.SelectedValues;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.TextValue;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ToolCall;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ToolKind;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ToolLocation;
import io.github.shiftinglabs.codej.cli.antigravity.AcpProtocol.ValueType;
import io.github.shiftinglabs.codej.cli.driver.ProviderAgentEventSource;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalDecision;
import io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest;
import io.github.shiftinglabs.codej.cli.process.SecretRedactor;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static io.github.shiftinglabs.codej.cli.driver.ProviderApprovalRequest.Decision.DENY;
import static java.util.Objects.requireNonNull;

/** Adapter from ACP interactions to the existing provider-neutral approval and question contract. */
final class AcpInteractions
{
    private static final ElicitationReply DECLINED = new ElicitationReply(FormAction.DECLINE, Optional.empty());

    private AcpInteractions() {}

    static HostReply denied(HostRequest request)
    {
        return request instanceof PermissionRequest permission ? permissionOutcome(permission.params(), PermissionOptionKind.REJECT_ONCE) : DECLINED;
    }

    static CompletableFuture<HostReply> edit(PermissionParams params, Path checkout, ProviderAgentEventSource source, SecretRedactor redactor,
            Function<ProviderApprovalRequest, CompletableFuture<ProviderApprovalDecision>> ask)
    {
        Optional<ToolCall> edit = params.toolCall().filter(call -> call.kind().filter(ToolKind.EDIT::equals).isPresent());
        PermissionReply allowed = permissionOutcome(params, PermissionOptionKind.ALLOW_ONCE);
        PermissionReply denied = permissionOutcome(params, PermissionOptionKind.REJECT_ONCE);
        if (edit.isEmpty() || allowed.outcome().outcome() != Outcome.SELECTED) {
            return CompletableFuture.completedFuture(denied);
        }
        ToolCall call = edit.orElseThrow();
        Optional<String> firstPath = Optional.empty();
        try {
            for (ToolLocation location : call.locations()) {
                if (location.path().isBlank()) {
                    return CompletableFuture.completedFuture(denied);
                }
                Path path = checkout.resolve(location.path()).normalize();
                if (path.equals(checkout) || !path.startsWith(checkout)) {
                    return CompletableFuture.completedFuture(denied);
                }
                if (firstPath.isEmpty()) {
                    firstPath = Optional.of(redactor.redact(path.toString()));
                }
            }
        }
        catch (InvalidPathException ignored) {
            return CompletableFuture.completedFuture(denied);
        }
        String title = display(call.title(), "Edit checkout file", redactor);
        return ask.apply(approval(ProviderApprovalRequest.Kind.FILE_CHANGE, title, new FileChange(firstPath), ImmutableList.of("edit"), source))
                .thenApply(decision -> decision.decision() == ProviderApprovalDecision.Decision.ALLOW ? allowed : denied);
    }

    private static PermissionReply permissionOutcome(PermissionParams params, PermissionOptionKind kind)
    {
        for (PermissionOption option : params.options()) {
            if (option.kind().filter(kind::equals).isPresent() && !option.optionId().isBlank()) {
                return new PermissionReply(new PermissionOutcome(Outcome.SELECTED, Optional.of(option.optionId())));
            }
        }
        return new PermissionReply(new PermissionOutcome(Outcome.CANCELLED, Optional.empty()));
    }

    static CompletableFuture<HostReply> question(ElicitationParams params, ProviderAgentEventSource source, SecretRedactor redactor,
            Function<ProviderApprovalRequest, CompletableFuture<ProviderApprovalDecision>> ask)
    {
        Optional<FormSchema> schemaInput = params.requestedSchema().filter(schema -> schema.type().filter(ValueType.OBJECT::equals).isPresent());
        if (params.mode().filter(FormMode.FORM::equals).isEmpty() || schemaInput.isEmpty()) {
            return CompletableFuture.completedFuture(DECLINED);
        }
        FormSchema schema = schemaInput.orElseThrow();
        if (schema.properties().isEmpty() || schema.properties().size() > 16) {
            return CompletableFuture.completedFuture(DECLINED);
        }
        var questions = ImmutableList.<HostQuestion>builder();
        Map<String, Field> fields = new LinkedHashMap<>();
        String message = display(params.message(), "Antigravity question", redactor);
        for (var property : schema.properties().entrySet()) {
            FormField definition = property.getValue();
            String title = display(definition.title(), schema.properties().size() == 1 ? message : redactor.redact(property.getKey()), redactor);
            String question = display(definition.description(), title, redactor);
            if (fields.containsKey(question)) {
                return CompletableFuture.completedFuture(DECLINED);
            }
            Optional<Field> field = field(property.getKey(), definition, schema.required(), redactor);
            if (field.isEmpty()) {
                return CompletableFuture.completedFuture(DECLINED);
            }
            Field value = field.orElseThrow();
            fields.put(question, value);
            List<Question.Option> options = value.options().entrySet().stream()
                    .map(choice -> new Question.Option(choice.getKey(), choice.getValue().description()))
                    .collect(toImmutableList());
            questions.add(new HostQuestion(question, title, value.multiple(), options));
        }
        var approval = approval(ProviderApprovalRequest.Kind.TOOL, message, new HostQuestions(questions.build()),
                ImmutableList.of(ProviderApprovalRequest.QUESTION_TOOL), source);
        return ask.apply(approval)
                .thenApply(decision -> answer(fields, decision));
    }

    private static ProviderApprovalRequest approval(ProviderApprovalRequest.Kind kind, String title, Object detail, List<String> requested, ProviderAgentEventSource source)
    {
        return new ProviderApprovalRequest(kind, title, AcpClient.encode(detail), requested, ImmutableList.of(ALLOW_ONCE, DENY), true, source, Instant.now());
    }

    private static Optional<Field> field(String key, FormField schema, List<String> required, SecretRedactor redactor)
    {
        Optional<ValueType> supported = schema.type().filter(type -> type != ValueType.OBJECT);
        if (supported.isEmpty()) {
            return Optional.empty();
        }
        ValueType type = supported.orElseThrow();
        boolean multiple = type == ValueType.ARRAY;
        if (multiple && schema.items().isEmpty()) {
            return Optional.empty();
        }
        FormField choices = multiple ? schema.items().orElseThrow() : schema;
        Map<String, Choice> options = new LinkedHashMap<>();
        if (type == ValueType.BOOLEAN) {
            options.put("Yes", new Choice("true", ""));
            options.put("No", new Choice("false", ""));
        }
        for (String value : choices.choices()) {
            if (!addChoice(options, new Choice(value, ""), redactor.redact(value), multiple)) {
                return Optional.empty();
            }
        }
        for (FormChoice option : multiple ? choices.anyOf() : choices.oneOf()) {
            if (!addChoice(options, new Choice(option.value(), display(option.description(), "", redactor)),
                    display(option.title(), redactor.redact(option.value()), redactor), multiple)) {
                return Optional.empty();
            }
        }
        return multiple && options.isEmpty() ? Optional.empty() : Optional.of(new Field(key, type, required.contains(key), options));
    }

    private static boolean addChoice(Map<String, Choice> options, Choice choice, String label, boolean multiple)
    {
        // The shared question handler encodes multiple selections with a comma and a space.
        return !label.isBlank() && !(multiple && label.contains(", ")) && options.putIfAbsent(label, choice) == null;
    }

    private static ElicitationReply answer(Map<String, Field> fields, ProviderApprovalDecision decision)
    {
        if (decision.decision() != ProviderApprovalDecision.Decision.ALLOW) {
            return DECLINED;
        }
        var content = ImmutableMap.<String, FormValue>builder();
        for (var entry : fields.entrySet()) {
            String answer = decision.answers().get(entry.getKey());
            Field field = entry.getValue();
            if (answer == null) {
                if (field.required()) {
                    return DECLINED;
                }
                continue;
            }
            Optional<FormValue> value = field.value(answer);
            if (value.isEmpty()) {
                return DECLINED;
            }
            content.put(field.name(), value.orElseThrow());
        }
        return new ElicitationReply(FormAction.ACCEPT, Optional.of(content.buildOrThrow()));
    }

    private static String display(Optional<String> value, String fallback, SecretRedactor redactor)
    {
        return Ascii.truncate(value.filter(text -> !text.isBlank()).map(redactor::redact).orElse(fallback), 1024, "...");
    }

    @JsonInclude(NON_ABSENT)
    private record FileChange(@JsonProperty("file_path") Optional<String> path)
    {
        private FileChange
        {
            requireNonNull(path, "path is null");
        }
    }

    private record HostQuestions(List<HostQuestion> questions)
    {
        private HostQuestions
        {
            questions = ImmutableList.copyOf(questions);
        }
    }

    private record HostQuestion(String question, String header, boolean multiSelect, List<Question.Option> options)
    {
        private HostQuestion
        {
            requireNonNull(question, "question is null");
            requireNonNull(header, "header is null");
            options = ImmutableList.copyOf(options);
        }
    }

    private record Choice(String value, String description)
    {
        private Choice
        {
            requireNonNull(value, "value is null");
            requireNonNull(description, "description is null");
        }
    }

    private record Field(String name, ValueType type, boolean required, Map<String, Choice> options)
    {
        private Field
        {
            requireNonNull(name, "name is null");
            requireNonNull(type, "type is null");
            options = ImmutableMap.copyOf(options);
        }

        boolean multiple()
        {
            return type == ValueType.ARRAY;
        }

        Optional<FormValue> value(String answer)
        {
            if (multiple()) {
                var values = ImmutableList.<String>builder();
                for (String label : Splitter.on(", ").split(answer)) {
                    Choice choice = options.get(label);
                    if (choice == null) {
                        return Optional.empty();
                    }
                    values.add(choice.value());
                }
                return Optional.of(new SelectedValues(values.build()));
            }
            if (options.isEmpty()) {
                return Optional.of(new TextValue(answer));
            }
            Choice choice = options.get(answer);
            if (choice == null) {
                return Optional.empty();
            }
            return Optional.of(type == ValueType.BOOLEAN ? new BooleanValue(Boolean.parseBoolean(choice.value())) : new TextValue(choice.value()));
        }
    }
}

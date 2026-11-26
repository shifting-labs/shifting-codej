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
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static com.fasterxml.jackson.annotation.Nulls.AS_EMPTY;
import static java.util.Objects.requireNonNull;

/** ACP records used by Antigravity. Provider metadata stays within this connector. */
final class AcpProtocol
{
    static final String VERSION = "2.0";
    static final Method<Initialize, Initialized> INITIALIZE = new Method<>("initialize", Initialized.class);
    static final Method<SessionSetup, SessionDetails> NEW_SESSION = new Method<>("session/new", SessionDetails.class);
    static final Method<SessionSetup, SessionDetails> RESUME_SESSION = new Method<>("session/resume", SessionDetails.class);
    static final Method<SetMode, Empty> SET_MODE = new Method<>("session/set_mode", Empty.class);
    static final Method<SetModel, Empty> SET_MODEL = new Method<>("session/set_model", Empty.class);
    static final Method<SetConfig, Configured> SET_CONFIG = new Method<>("session/set_config_option", Configured.class);
    static final Method<Prompt, PromptResult> PROMPT = new Method<>("session/prompt", PromptResult.class);
    static final Method<SessionId, Empty> CLOSE_SESSION = new Method<>("session/close", Empty.class);
    static final String CANCEL_SESSION = "session/cancel";

    private AcpProtocol() {}

    record Method<P, R>(String name, Class<R> resultType)
    {
        Method
        {
            requireNonNull(name, "name is null");
            requireNonNull(resultType, "resultType is null");
        }
    }

    // Bind the payload after dispatching by method or matching a response to its pending request.
    record Incoming(String jsonrpc, Optional<RpcId> id, Optional<String> method, Optional<TokenBuffer> params, Optional<TokenBuffer> result, Optional<RpcError> error)
    {
        Incoming
        {
            requireNonNull(jsonrpc, "jsonrpc is null");
            requireNonNull(id, "id is null");
            requireNonNull(method, "method is null");
            requireNonNull(params, "params is null");
            requireNonNull(result, "result is null");
            requireNonNull(error, "error is null");
        }
    }

    sealed interface Outgoing
            permits Call, Notification, Success, Rejection {}

    record Call<P>(String jsonrpc, long id, String method, P params)
            implements Outgoing
    {
        Call
        {
            requireNonNull(jsonrpc, "jsonrpc is null");
            requireNonNull(method, "method is null");
            requireNonNull(params, "params is null");
        }
    }

    record Notification(String jsonrpc, String method, SessionId params)
            implements Outgoing
    {
        Notification
        {
            requireNonNull(jsonrpc, "jsonrpc is null");
            requireNonNull(method, "method is null");
            requireNonNull(params, "params is null");
        }
    }

    record Success(String jsonrpc, RpcId id, HostReply result)
            implements Outgoing
    {
        Success
        {
            requireNonNull(jsonrpc, "jsonrpc is null");
            requireNonNull(id, "id is null");
            requireNonNull(result, "result is null");
        }
    }

    record Rejection(String jsonrpc, RpcId id, RpcError error)
            implements Outgoing
    {
        Rejection
        {
            requireNonNull(jsonrpc, "jsonrpc is null");
            requireNonNull(id, "id is null");
            requireNonNull(error, "error is null");
        }
    }

    record RpcError(int code, Optional<String> message)
    {
        RpcError
        {
            requireNonNull(message, "message is null");
        }
    }

    @JsonDeserialize(using = RpcIdDeserializer.class)
    sealed interface RpcId
            permits NumberId, TextId
    {
        @JsonValue
        Object value();
    }

    record NumberId(Long value)
            implements RpcId
    {
        NumberId
        {
            requireNonNull(value, "value is null");
        }
    }

    record TextId(String value)
            implements RpcId
    {
        TextId
        {
            requireNonNull(value, "value is null");
        }
    }

    static final class RpcIdDeserializer
            extends JsonDeserializer<RpcId>
    {
        @Override
        public RpcId deserialize(JsonParser parser, DeserializationContext context)
                throws IOException
        {
            return switch (parser.currentToken()) {
                case VALUE_NUMBER_INT -> new NumberId(parser.getLongValue());
                case VALUE_STRING -> new TextId(parser.getText());
                default -> (RpcId) context.handleUnexpectedToken(RpcId.class, parser);
            };
        }
    }

    record Empty() {}

    record Initialize(int protocolVersion, ClientCapabilities clientCapabilities, ClientInfo clientInfo)
    {
        Initialize
        {
            requireNonNull(clientCapabilities, "clientCapabilities is null");
            requireNonNull(clientInfo, "clientInfo is null");
        }
    }

    record ClientInfo(String name, String version)
    {
        ClientInfo
        {
            requireNonNull(name, "name is null");
            requireNonNull(version, "version is null");
        }
    }

    @JsonInclude(NON_ABSENT)
    record ClientCapabilities(Optional<ElicitationCapability> elicitation)
    {
        ClientCapabilities
        {
            requireNonNull(elicitation, "elicitation is null");
        }
    }

    record ElicitationCapability(FormCapability form)
    {
        ElicitationCapability
        {
            requireNonNull(form, "form is null");
        }
    }

    record FormCapability() {}

    record Initialized(int protocolVersion, Optional<AgentCapabilities> agentCapabilities)
    {
        Initialized
        {
            requireNonNull(agentCapabilities, "agentCapabilities is null");
        }
    }

    record AgentCapabilities(
            Optional<SessionCapabilities> sessionCapabilities,
            Optional<McpCapabilities> mcpCapabilities,
            @JsonProperty("_meta") Optional<Metadata<Boolean>> metadata)
    {
        AgentCapabilities
        {
            requireNonNull(sessionCapabilities, "sessionCapabilities is null");
            requireNonNull(mcpCapabilities, "mcpCapabilities is null");
            requireNonNull(metadata, "metadata is null");
        }

        boolean supportsClose()
        {
            return sessionCapabilities.flatMap(SessionCapabilities::close).isPresent();
        }

        boolean supportsResume()
        {
            return sessionCapabilities.flatMap(SessionCapabilities::resume).isPresent();
        }

        boolean supportsHttpTools()
        {
            return mcpCapabilities.map(McpCapabilities::http).orElse(false);
        }

        boolean supportsInstructions()
        {
            return metadata.flatMap(Metadata::antigravity)
                    .flatMap(AntigravityMetadata::systemPrompt)
                    .flatMap(SystemPrompt::append)
                    .orElse(false);
        }
    }

    record SessionCapabilities(Optional<CloseCapability> close, Optional<ResumeCapability> resume)
    {
        SessionCapabilities
        {
            requireNonNull(close, "close is null");
            requireNonNull(resume, "resume is null");
        }
    }

    record CloseCapability() {}

    record ResumeCapability() {}

    record McpCapabilities(boolean http) {}

    @JsonInclude(NON_ABSENT)
    record SessionSetup(String cwd, Optional<String> sessionId, List<HttpMcpServer> mcpServers, @JsonProperty("_meta") Optional<Metadata<String>> metadata)
    {
        SessionSetup
        {
            requireNonNull(cwd, "cwd is null");
            requireNonNull(sessionId, "sessionId is null");
            mcpServers = ImmutableList.copyOf(mcpServers);
            requireNonNull(metadata, "metadata is null");
        }
    }

    record Metadata<T>(Optional<AntigravityMetadata<T>> antigravity)
    {
        Metadata
        {
            requireNonNull(antigravity, "antigravity is null");
        }
    }

    record AntigravityMetadata<T>(Optional<SystemPrompt<T>> systemPrompt)
    {
        AntigravityMetadata
        {
            requireNonNull(systemPrompt, "systemPrompt is null");
        }
    }

    record SystemPrompt<T>(Optional<T> append)
    {
        SystemPrompt
        {
            requireNonNull(append, "append is null");
        }
    }

    record SessionId(String sessionId)
    {
        SessionId
        {
            requireNonNull(sessionId, "sessionId is null");
        }
    }

    record SessionDetails(Optional<String> sessionId, Optional<Models> models, @JsonSetter(nulls = AS_EMPTY) List<ConfigOption> configOptions)
    {
        SessionDetails
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(models, "models is null");
            configOptions = ImmutableList.copyOf(configOptions);
        }
    }

    record Models(@JsonSetter(nulls = AS_EMPTY) List<ModelOption> availableModels)
    {
        Models
        {
            availableModels = ImmutableList.copyOf(availableModels);
        }
    }

    record ModelOption(String modelId)
    {
        ModelOption
        {
            requireNonNull(modelId, "modelId is null");
        }
    }

    record SetModel(String sessionId, String modelId)
    {
        SetModel
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(modelId, "modelId is null");
        }
    }

    record ConfigOption(String id, Optional<String> category)
    {
        ConfigOption
        {
            requireNonNull(id, "id is null");
            requireNonNull(category, "category is null");
        }
    }

    record SetConfig(String sessionId, String configId, String value)
    {
        SetConfig
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(configId, "configId is null");
            requireNonNull(value, "value is null");
        }
    }

    record Configured(@JsonSetter(nulls = AS_EMPTY) List<ConfigOption> configOptions)
    {
        Configured
        {
            configOptions = ImmutableList.copyOf(configOptions);
        }
    }

    record Prompt(String sessionId, List<Content> prompt)
    {
        Prompt
        {
            requireNonNull(sessionId, "sessionId is null");
            prompt = ImmutableList.copyOf(prompt);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = IgnoredContent.class)
    @JsonSubTypes(@JsonSubTypes.Type(name = "text", value = Text.class))
    sealed interface Content
            permits Text, IgnoredContent {}

    record Text(String text)
            implements Content
    {
        Text
        {
            requireNonNull(text, "text is null");
        }
    }

    record IgnoredContent()
            implements Content {}

    record PromptResult(Optional<String> stopReason, Optional<Usage> usage)
    {
        PromptResult
        {
            requireNonNull(stopReason, "stopReason is null");
            requireNonNull(usage, "usage is null");
        }
    }

    record Usage(
            @JsonDeserialize(using = TokenCountDeserializer.class) Optional<Long> inputTokens,
            @JsonDeserialize(using = TokenCountDeserializer.class) Optional<Long> cachedReadTokens,
            @JsonDeserialize(using = TokenCountDeserializer.class) Optional<Long> cachedWriteTokens,
            @JsonDeserialize(using = TokenCountDeserializer.class) Optional<Long> outputTokens)
    {
        Usage
        {
            requireNonNull(inputTokens, "inputTokens is null");
            requireNonNull(cachedReadTokens, "cachedReadTokens is null");
            requireNonNull(cachedWriteTokens, "cachedWriteTokens is null");
            requireNonNull(outputTokens, "outputTokens is null");
        }
    }

    // Invalid optional usage must not discard an otherwise successful prompt.
    static final class TokenCountDeserializer
            extends JsonDeserializer<Optional<Long>>
    {
        @Override
        public Optional<Long> deserialize(JsonParser parser, DeserializationContext context)
                throws IOException
        {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                parser.skipChildren();
                return Optional.of(-1L);
            }
            try {
                return Optional.of(parser.getBigIntegerValue().longValueExact());
            }
            catch (ArithmeticException ignored) {
                return Optional.of(-1L);
            }
        }

        @Override
        public Optional<Long> getNullValue(DeserializationContext context)
        {
            return Optional.of(-1L);
        }

        @Override
        public Optional<Long> getAbsentValue(DeserializationContext context)
        {
            return Optional.empty();
        }
    }

    record SessionNotification(String sessionId, SessionUpdate update)
    {
        SessionNotification
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(update, "update is null");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sessionUpdate", defaultImpl = IgnoredUpdate.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "agent_message_chunk", value = MessageChunk.class),
            @JsonSubTypes.Type(name = "tool_call", value = ToolStarted.class),
            @JsonSubTypes.Type(name = "tool_call_update", value = ToolChanged.class),
    })
    sealed interface SessionUpdate
            permits MessageChunk, ToolActivity, IgnoredUpdate {}

    record MessageChunk(Content content)
            implements SessionUpdate
    {
        MessageChunk
        {
            requireNonNull(content, "content is null");
        }
    }

    sealed interface ToolActivity
            extends SessionUpdate
            permits ToolStarted, ToolChanged
    {
        String toolCallId();

        Optional<String> title();

        Optional<ToolStatus> status();
    }

    record ToolStarted(String toolCallId, Optional<String> title, Optional<ToolStatus> status)
            implements ToolActivity
    {
        ToolStarted
        {
            requireNonNull(toolCallId, "toolCallId is null");
            requireNonNull(title, "title is null");
            requireNonNull(status, "status is null");
        }
    }

    record ToolChanged(String toolCallId, Optional<String> title, Optional<ToolStatus> status)
            implements ToolActivity
    {
        ToolChanged
        {
            requireNonNull(toolCallId, "toolCallId is null");
            requireNonNull(title, "title is null");
            requireNonNull(status, "status is null");
        }
    }

    enum ToolStatus
    {
        @JsonProperty("pending") PENDING,
        @JsonProperty("completed") COMPLETED,
        @JsonProperty("failed") FAILED
    }

    record IgnoredUpdate()
            implements SessionUpdate {}

    record HttpMcpServer(String name, URI url, List<HttpHeader> headers)
    {
        HttpMcpServer
        {
            requireNonNull(name, "name is null");
            requireNonNull(url, "url is null");
            headers = ImmutableList.copyOf(headers);
        }

        @JsonProperty
        String type()
        {
            return "http";
        }
    }

    record SetMode(String sessionId, Mode modeId)
    {
        SetMode
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(modeId, "modeId is null");
        }
    }

    enum Mode
    {
        @JsonProperty("default") REVIEW_EDITS,
        @JsonProperty("accept-edits") ACCEPT_EDITS
    }

    sealed interface HostRequest
            permits PermissionRequest, ElicitationRequest
    {
        SessionParams params();
    }

    record PermissionRequest(PermissionParams params)
            implements HostRequest
    {
        PermissionRequest
        {
            requireNonNull(params, "params is null");
        }
    }

    record ElicitationRequest(ElicitationParams params)
            implements HostRequest
    {
        ElicitationRequest
        {
            requireNonNull(params, "params is null");
        }
    }

    sealed interface SessionParams
            permits PermissionParams, ElicitationParams
    {
        String sessionId();
    }

    record PermissionParams(String sessionId, Optional<ToolCall> toolCall, @JsonSetter(nulls = AS_EMPTY) List<PermissionOption> options)
            implements SessionParams
    {
        PermissionParams
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(toolCall, "toolCall is null");
            options = ImmutableList.copyOf(options);
        }
    }

    record ToolCall(Optional<ToolKind> kind, Optional<String> title, @JsonSetter(nulls = AS_EMPTY) List<ToolLocation> locations)
    {
        ToolCall
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(title, "title is null");
            locations = ImmutableList.copyOf(locations);
        }
    }

    enum ToolKind
    {
        @JsonProperty("edit") EDIT
    }

    record ToolLocation(String path)
    {
        ToolLocation
        {
            requireNonNull(path, "path is null");
        }
    }

    record PermissionOption(String optionId, Optional<PermissionOptionKind> kind)
    {
        PermissionOption
        {
            requireNonNull(optionId, "optionId is null");
            requireNonNull(kind, "kind is null");
        }
    }

    enum PermissionOptionKind
    {
        @JsonProperty("allow_once") ALLOW_ONCE,
        @JsonProperty("reject_once") REJECT_ONCE
    }

    record ElicitationParams(String sessionId, Optional<FormMode> mode, Optional<String> message, Optional<FormSchema> requestedSchema)
            implements SessionParams
    {
        ElicitationParams
        {
            requireNonNull(sessionId, "sessionId is null");
            requireNonNull(mode, "mode is null");
            requireNonNull(message, "message is null");
            requireNonNull(requestedSchema, "requestedSchema is null");
        }
    }

    enum FormMode
    {
        @JsonProperty("form") FORM
    }

    record FormSchema(Optional<ValueType> type, @JsonSetter(nulls = AS_EMPTY) Map<String, FormField> properties, @JsonSetter(nulls = AS_EMPTY) List<String> required)
    {
        FormSchema
        {
            requireNonNull(type, "type is null");
            properties = ImmutableMap.copyOf(properties);
            required = ImmutableList.copyOf(required);
        }
    }

    record FormField(
            Optional<ValueType> type, Optional<String> title, Optional<String> description, @JsonProperty("enum") @JsonSetter(nulls = AS_EMPTY) List<String> choices,
            @JsonSetter(nulls = AS_EMPTY) List<FormChoice> oneOf, @JsonSetter(nulls = AS_EMPTY) List<FormChoice> anyOf, Optional<FormField> items)
    {
        FormField
        {
            requireNonNull(type, "type is null");
            requireNonNull(title, "title is null");
            requireNonNull(description, "description is null");
            choices = ImmutableList.copyOf(choices);
            oneOf = ImmutableList.copyOf(oneOf);
            anyOf = ImmutableList.copyOf(anyOf);
            requireNonNull(items, "items is null");
        }
    }

    record FormChoice(@JsonProperty("const") String value, Optional<String> title, Optional<String> description)
    {
        FormChoice
        {
            requireNonNull(value, "value is null");
            requireNonNull(title, "title is null");
            requireNonNull(description, "description is null");
        }
    }

    enum ValueType
    {
        @JsonProperty("object") OBJECT,
        @JsonProperty("string") TEXT,
        @JsonProperty("boolean") BOOLEAN,
        @JsonProperty("array") ARRAY
    }

    sealed interface HostReply
            permits PermissionReply, ElicitationReply {}

    record PermissionReply(PermissionOutcome outcome)
            implements HostReply
    {
        PermissionReply
        {
            requireNonNull(outcome, "outcome is null");
        }
    }

    @JsonInclude(NON_ABSENT)
    record PermissionOutcome(Outcome outcome, Optional<String> optionId)
    {
        PermissionOutcome
        {
            requireNonNull(outcome, "outcome is null");
            requireNonNull(optionId, "optionId is null");
        }
    }

    enum Outcome
    {
        @JsonProperty("selected") SELECTED,
        @JsonProperty("cancelled") CANCELLED
    }

    @JsonInclude(NON_ABSENT)
    record ElicitationReply(FormAction action, Optional<Map<String, FormValue>> content)
            implements HostReply
    {
        ElicitationReply
        {
            requireNonNull(action, "action is null");
            content = requireNonNull(content, "content is null").map(ImmutableMap::copyOf);
        }
    }

    enum FormAction
    {
        @JsonProperty("accept") ACCEPT,
        @JsonProperty("decline") DECLINE
    }

    sealed interface FormValue
            permits TextValue, BooleanValue, SelectedValues
    {
        @JsonValue
        Object value();
    }

    record TextValue(String value)
            implements FormValue
    {
        TextValue
        {
            requireNonNull(value, "value is null");
        }
    }

    record BooleanValue(Boolean value)
            implements FormValue
    {
        BooleanValue
        {
            requireNonNull(value, "value is null");
        }
    }

    record SelectedValues(List<String> value)
            implements FormValue
    {
        SelectedValues
        {
            value = ImmutableList.copyOf(value);
        }
    }

    record HttpHeader(String name, String value)
    {
        HttpHeader
        {
            requireNonNull(name, "name is null");
            requireNonNull(value, "value is null");
        }
    }
}

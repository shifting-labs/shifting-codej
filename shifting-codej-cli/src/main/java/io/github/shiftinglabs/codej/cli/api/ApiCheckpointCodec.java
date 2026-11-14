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
package io.github.shiftinglabs.codej.cli.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.type.ReferenceType;
import com.fasterxml.jackson.databind.util.AccessPattern;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.shiftinglabs.codej.cli.driver.AgentAttachment;
import io.github.shiftinglabs.codej.cli.driver.AgentLaneId;
import io.github.shiftinglabs.codej.cli.spi.ConnectorIdentity;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;
import io.github.shiftinglabs.codej.cli.spi.model.ModelReplayState;
import io.github.shiftinglabs.codej.cli.spi.model.ModelRequest;
import io.github.shiftinglabs.codej.cli.spi.model.ModelResponse;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

/** The version-1 wire boundary. Strict record binding never coerces untrusted checkpoint metadata. */
final class ApiCheckpointCodec
{
    static final ObjectMapper JSON = mapper();

    private ApiCheckpointCodec() {}

    private static ObjectMapper mapper()
    {
        ObjectMapper mapper = JsonMapper.builder().addModule(new Jdk8Module()).addModule(optionalValues())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).serializationInclusion(NON_ABSENT).build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    private static SimpleModule optionalValues()
    {
        return new SimpleModule().setDeserializerModifier(new BeanDeserializerModifier()
        {
            @Override
            public JsonDeserializer<?> modifyReferenceDeserializer(DeserializationConfig config, ReferenceType type, BeanDescription description,
                    JsonDeserializer<?> deserializer)
            {
                return type.hasRawClass(Optional.class) ? new StrictOptional(deserializer) : deserializer;
            }
        });
    }

    /** Missing version-1 fields are absent; an explicit null is malformed metadata, never a default. */
    private static final class StrictOptional
            extends DelegatingDeserializer
    {
        StrictOptional(JsonDeserializer<?> delegate) { super(delegate); }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) { return new StrictOptional(delegate); }

        @Override
        public Object getAbsentValue(DeserializationContext context) { return Optional.empty(); }

        @Override
        public Object getNullValue(DeserializationContext context)
                throws JsonMappingException
        {
            return context.reportInputMismatch(Optional.class, "Explicit null checkpoint fields are unsupported");
        }

        @Override
        public AccessPattern getNullAccessPattern() { return AccessPattern.DYNAMIC; }
    }

    static ApiCheckpoint decode(byte[] bytes)
            throws IOException
    {
        try {
            CheckpointData data = JSON.readValue(bytes, CheckpointData.class);
            return new ApiCheckpoint(data.version(), data.id(), new ConnectorIdentity(data.connector(), data.profile()), new AgentLaneId(data.lane()),
                    phase(data.phase()), data.journal().stream().map(EventData::entry).toList(), data.receipts().stream().map(ReceiptData::receipt).toList(),
                    data.replay().map(ReplayData::replay), data.setup().map(SetupData::setup), data.input().map(InputData::input));
        }
        catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid API checkpoint", failure);
        }
    }

    static byte[] encode(ApiCheckpoint state)
            throws IOException
    {
        return JSON.writeValueAsBytes(new CheckpointData(state.version(), state.id(), state.identity().connectorId(), state.identity().profileId(),
                state.lane().value(), state.phase().name().toLowerCase(Locale.ROOT), state.journal().stream().map(ApiCheckpointCodec::event).toList(),
                state.receipts().stream().map(ReceiptData::of).toList(), state.replay().map(ReplayData::of), state.setup().map(SetupData::of),
                state.input().map(ApiCheckpointCodec::input)));
    }

    static ApiRequestSetup decodeSetup(byte[] bytes)
            throws IOException
    {
        try {
            return JSON.readValue(bytes, SetupData.class).setup();
        }
        catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid API request setup", failure);
        }
    }

    private static ApiCheckpoint.Phase phase(String value)
    {
        return switch (value) {
            case "ready" -> ApiCheckpoint.Phase.READY;
            case "request" -> ApiCheckpoint.Phase.REQUEST;
            case "tools" -> ApiCheckpoint.Phase.TOOLS;
            case "blocked" -> ApiCheckpoint.Phase.BLOCKED;
            default -> throw new IllegalArgumentException("Unknown checkpoint phase");
        };
    }

    private record CheckpointData(@JsonProperty(required = true) int version, @JsonProperty(required = true) String id,
            @JsonProperty(required = true) String connector, @JsonProperty(required = true) String profile, @JsonProperty(required = true) String lane,
            @JsonProperty(required = true) String phase, @JsonProperty(required = true) List<EventData> journal,
            @JsonProperty(required = true) List<ReceiptData> receipts, Optional<ReplayData> replay,
            Optional<SetupData> setup, Optional<InputData> input) {}

    private record SetupData(@JsonProperty(required = true) String model, @JsonProperty(required = true) String instructions,
            Optional<String> effort, Optional<String> tier,
            @JsonProperty(required = true) List<DefinitionData> tools, Optional<List<ToolRegistration.Contract>> toolContracts)
    {
        ApiRequestSetup setup()
        {
            return new ApiRequestSetup(model, instructions, effort, tier, tools.stream().map(DefinitionData::definition).toList(), toolContracts);
        }

        static SetupData of(ApiRequestSetup value)
        {
            return new SetupData(value.model(), value.instructions(), value.effort(), value.tier(),
                    value.tools().stream().map(tool -> new DefinitionData(tool.name(), tool.description(), tool.inputSchema())).toList(), value.toolContracts());
        }
    }

    private record DefinitionData(@JsonProperty(required = true) String name, @JsonProperty(required = true) String description,
            @JsonProperty(required = true) String schema)
    {
        ModelRequest.ToolDefinition definition() { return new ModelRequest.ToolDefinition(name, description, schema); }
    }

    private record ReplayData(@JsonProperty(required = true) String provider, @JsonProperty(required = true) int version,
            @JsonProperty(required = true) String payload)
    {
        ModelReplayState replay() { return new ModelReplayState(provider, version, payload); }

        static ReplayData of(ModelReplayState value) { return new ReplayData(value.providerId(), value.formatVersion(), value.payload()); }
    }

    private record OutcomeData(@JsonProperty(required = true) String text, @JsonProperty(required = true) boolean error)
    {
        ToolRegistration.Result result() { return new ToolRegistration.Result(text, error); }
    }

    private record ReceiptData(@JsonProperty(required = true) String id, @JsonProperty(required = true) String name,
            @JsonProperty(required = true) String arguments, Optional<String> eventId,
            @JsonProperty(required = true) boolean started, Optional<OutcomeData> result)
    {
        ApiToolReceipt receipt() { return new ApiToolReceipt(id, name, arguments, eventId, started, result.map(OutcomeData::result)); }

        static ReceiptData of(ApiToolReceipt value)
        {
            return new ReceiptData(value.id(), value.name(), value.arguments(), value.eventId(), value.started(),
                    value.result().map(result -> new OutcomeData(result.text(), result.error())));
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = UserInputData.class, name = "user"), @JsonSubTypes.Type(value = ResultsInputData.class, name = "results")})
    private sealed interface InputData permits UserInputData, ResultsInputData
    {
        ModelInput input();
    }

    private record AttachmentData(@JsonProperty(required = true) String id, @JsonProperty(required = true) String name,
            @JsonProperty(required = true) String mediaType, @JsonProperty(required = true) String path)
    {
        AgentAttachment attachment() { return new AgentAttachment(id, name, mediaType, Path.of(path)); }
    }

    private record UserInputData(@JsonProperty(required = true) String text, @JsonProperty(required = true) List<AttachmentData> attachments)
            implements InputData
    {
        @Override
        public ModelInput.UserMessage input() { return new ModelInput.UserMessage(text, attachments.stream().map(AttachmentData::attachment).toList()); }
    }

    private record ResultData(@JsonProperty(required = true) String id, @JsonProperty(required = true) String text, @JsonProperty(required = true) boolean error)
    {
        ModelInput.ToolResult result() { return new ModelInput.ToolResult(id, text, error); }
    }

    private record ResultsInputData(@JsonProperty(required = true) List<ResultData> results) implements InputData
    {
        @Override
        public ModelInput.ToolResults input() { return new ModelInput.ToolResults(results.stream().map(ResultData::result).toList()); }
    }

    private static InputData input(ModelInput value)
    {
        return switch (value) {
            case ModelInput.UserMessage user -> userInput(user);
            case ModelInput.ToolResults batch -> new ResultsInputData(batch.results().stream()
                    .map(result -> new ResultData(result.callId(), result.text(), result.error())).toList());
        };
    }

    private static UserInputData userInput(ModelInput.UserMessage value)
    {
        return new UserInputData(value.text(), value.attachments().stream()
                .map(item -> new AttachmentData(item.id(), item.name(), item.mediaType(), item.path().toString())).toList());
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = UserData.class, name = "user"), @JsonSubTypes.Type(value = RequestData.class, name = "request"),
            @JsonSubTypes.Type(value = ResponseData.class, name = "response"), @JsonSubTypes.Type(value = ToolStartedData.class, name = "tool-start"),
            @JsonSubTypes.Type(value = ToolResultData.class, name = "tool-result"), @JsonSubTypes.Type(value = CodingLaunchData.class, name = "coding-launch"),
            @JsonSubTypes.Type(value = PreparedResourceData.class, name = "tool-resource")})
    private sealed interface EventData permits UserData, RequestData, ResponseData, ToolStartedData, ToolResultData, CodingLaunchData, PreparedResourceData
    {
        ApiJournal.Entry entry();
    }

    private record UserData(@JsonProperty(required = true) String turn, @JsonProperty(required = true) UserInputData input) implements EventData
    {
        @Override
        public ApiJournal.User entry() { return new ApiJournal.User(turn, input.input()); }
    }

    private record RequestData(@JsonProperty(required = true) String id) implements EventData
    {
        @Override
        public ApiJournal.Request entry() { return new ApiJournal.Request(id); }
    }

    private record ResponseData(@JsonProperty(required = true) String id, @JsonProperty(required = true) String stop) implements EventData
    {
        @Override
        public ApiJournal.Response entry() { return new ApiJournal.Response(id, ModelResponse.StopReason.valueOf(stop)); }
    }

    private record ToolStartedData(@JsonProperty(required = true) String id) implements EventData
    {
        @Override
        public ApiJournal.ToolStarted entry() { return new ApiJournal.ToolStarted(id); }
    }

    private record ToolResultData(@JsonProperty(required = true) String id, @JsonProperty(required = true) String text,
            @JsonProperty(required = true) boolean error) implements EventData
    {
        @Override
        public ApiJournal.ToolResult entry() { return new ApiJournal.ToolResult(id, text, error); }
    }

    private record CodingLaunchData(@JsonProperty(required = true) String launchId) implements EventData
    {
        @Override
        public ApiJournal.CodingLaunch entry() { return new ApiJournal.CodingLaunch(launchId); }
    }

    private record PreparedResourceData(@JsonProperty(required = true) String id, @JsonProperty(required = true) ToolResource resource) implements EventData
    {
        @Override
        public ApiJournal.PreparedResource entry() { return new ApiJournal.PreparedResource(id, resource); }
    }

    private static EventData event(ApiJournal.Entry entry)
    {
        return switch (entry) {
            case ApiJournal.User user -> new UserData(user.turn(), userInput(user.input()));
            case ApiJournal.Request request -> new RequestData(request.id());
            case ApiJournal.Response response -> new ResponseData(response.id(), response.stop().name());
            case ApiJournal.ToolStarted started -> new ToolStartedData(started.id());
            case ApiJournal.ToolResult result -> new ToolResultData(result.id(), result.text(), result.error());
            case ApiJournal.CodingLaunch launch -> new CodingLaunchData(launch.launchId());
            case ApiJournal.PreparedResource prepared -> new PreparedResourceData(prepared.id(), prepared.resource());
        };
    }
}

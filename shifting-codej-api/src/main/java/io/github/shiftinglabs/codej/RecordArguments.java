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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

/** A record class as a tool's arguments: its components as a JSON schema, and each call's JSON bound into a new record. */
final class RecordArguments
{
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private RecordArguments() {}

    static String schema(Class<? extends Record> arguments)
    {
        try {
            return JSON.writeValueAsString(object(arguments));
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The call's arguments as a record; a missing, extra or mistyped argument fails the call with a message the agent can act on. */
    static <A extends Record> A bind(String json, Class<A> arguments)
    {
        A bound;
        try {
            bound = JSON.readValue(json, arguments);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid arguments: " + failure.getOriginalMessage(), failure);
        }
        checkArgument(bound != null, "Invalid arguments: expected an object");
        requirePresent(bound);
        return bound;
    }

    private static void requirePresent(Record record)
    {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object value;
            try {
                Method accessor = component.getAccessor();
                // A caller's own record is often not public, and Jackson bound it the same way; only a sealed module refuses.
                accessor.setAccessible(true);
                value = accessor.invoke(record);
            }
            catch (IllegalAccessException | InvocationTargetException | InaccessibleObjectException failure) {
                throw new IllegalArgumentException("Cannot read argument " + component.getName() + ": " + failure.getMessage(), failure);
            }
            checkArgument(value != null, "Missing argument: %s", component.getName());
            if (value instanceof Record nested) {
                requirePresent(nested);
            }
        }
    }

    private static ObjectNode object(Class<?> record)
    {
        checkArgument(record.isRecord(), "Not a record: %s", record.getName());
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = JSON.createArrayNode();
        for (RecordComponent component : record.getRecordComponents()) {
            Type type = component.getGenericType();
            if (raw(type) == Optional.class) {
                properties.set(component.getName(), schema(argument(type), component));
            }
            else {
                properties.set(component.getName(), schema(type, component));
                required.add(component.getName());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema.put("additionalProperties", false);
    }

    private static ObjectNode schema(Type type, RecordComponent component)
    {
        Class<?> raw = raw(type);
        if (raw == String.class) {
            return JSON.createObjectNode().put("type", "string");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return JSON.createObjectNode().put("type", "boolean");
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            return JSON.createObjectNode().put("type", "integer");
        }
        if (raw == double.class || raw == Double.class) {
            return JSON.createObjectNode().put("type", "number");
        }
        if (raw.isEnum()) {
            ObjectNode schema = JSON.createObjectNode().put("type", "string");
            ArrayNode values = schema.putArray("enum");
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return schema;
        }
        if (raw == List.class) {
            ObjectNode schema = JSON.createObjectNode().put("type", "array");
            schema.set("items", schema(argument(type), component));
            return schema;
        }
        if (raw.isRecord()) {
            return object(raw);
        }
        throw new IllegalArgumentException("Unsupported argument type for " + component.getName() + ": " + type.getTypeName());
    }

    private static Class<?> raw(Type type)
    {
        if (type instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        throw new IllegalArgumentException("Unsupported argument type: " + type.getTypeName());
    }

    private static Type argument(Type type)
    {
        checkArgument(type instanceof ParameterizedType, "Give %s its element type", type.getTypeName());
        return ((ParameterizedType) type).getActualTypeArguments()[0];
    }
}

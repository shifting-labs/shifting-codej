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

import com.anthropic.core.JsonField;
import com.anthropic.core.JsonMissing;
import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;

/** Uses the SDK's explicit Jackson creators while preserving absent versus null fields without Kotlin reflection. */
final class AnthropicJson
{
    private AnthropicJson() {}

    static JsonMapper mapper()
    {
        return JsonMapper.builder().addModule(new Jdk8Module()).addModule(new JavaTimeModule())
                .disable(MapperFeature.AUTO_DETECT_GETTERS, MapperFeature.AUTO_DETECT_IS_GETTERS, MapperFeature.AUTO_DETECT_FIELDS,
                        MapperFeature.AUTO_DETECT_SETTERS, MapperFeature.AUTO_DETECT_CREATORS, MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .serializationInclusion(JsonInclude.Include.NON_ABSENT).annotationIntrospector(new JacksonAnnotationIntrospector()
                {
                    @Override
                    public Object findDeserializer(Annotated annotated)
                    {
                        if (annotated.getRawType() == JsonField.class || annotated.getRawType() == JsonValue.class) {
                            return Fields.class;
                        }
                        return super.findDeserializer(annotated);
                    }
                }).build();
    }

    /** Jackson constructs this through the introspector; the SDK still decodes every value and preserves unknown fields. */
    public static final class Fields
            extends JsonDeserializer<Object>
            implements ContextualDeserializer
    {
        private final JsonDeserializer<?> delegate;

        public Fields() { delegate = new JsonField.Deserializer(); }

        private Fields(JsonDeserializer<?> delegate) { this.delegate = delegate; }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property)
                throws JsonMappingException
        {
            if (context.getContextualType().getRawClass() == JsonValue.class) {
                return new Fields(new JsonValue.Deserializer());
            }
            return new Fields(new JsonField.Deserializer().createContextual(context, property));
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context)
                throws IOException
        {
            return delegate.deserialize(parser, context);
        }

        @Override
        public Object getNullValue(DeserializationContext context)
                throws JsonMappingException
        {
            return delegate.getNullValue(context);
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) { return JsonMissing.of(); }
    }
}

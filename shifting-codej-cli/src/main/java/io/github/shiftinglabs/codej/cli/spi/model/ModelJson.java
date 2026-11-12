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
package io.github.shiftinglabs.codej.cli.spi.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

final class ModelJson
{
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ModelJson() {}

    /** Validate the envelope without changing its spelling or including rejected contents in errors. */
    static void checkObject(String value, String name)
    {
        requireNonNull(value, name + " is null");
        JsonNode parsed;
        try {
            parsed = JSON.readTree(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        checkArgument(parsed != null && parsed.isObject(), name + " must be a JSON object");
    }
}

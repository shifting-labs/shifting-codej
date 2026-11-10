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
package io.github.shiftinglabs.codej.cli.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shiftinglabs.codej.Tool;

import java.util.Map;
import java.util.function.Function;

/** A sample tool with a schema given as a map, as the host application's review tools are built, for the driver tests. */
public final class ReviewTool
{
    private static final ObjectMapper SCHEMAS = new ObjectMapper();

    private ReviewTool() {}

    public static Tool of(String name, String description, Map<String, Object> inputSchema, Function<String, String> call)
    {
        try {
            return Tool.withSchema(name, description, SCHEMAS.writeValueAsString(inputSchema), call::apply);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Tool schema is not JSON: " + name, failure);
        }
    }
}

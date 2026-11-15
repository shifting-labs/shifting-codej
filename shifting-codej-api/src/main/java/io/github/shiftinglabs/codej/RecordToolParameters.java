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

import io.github.shiftinglabs.codej.tool.JsonSchemaParameters;
import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import io.github.shiftinglabs.codej.tool.ToolSchema;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Optional reflection-backed parameter adapter. The tool SPI itself requires no Class token.
 * Existing Tool.of record calls retain their legacy binding behavior through an internal mode.
 *
 * @param <P> the record value
 */
public final class RecordToolParameters<P extends Record>
        implements ToolParameters<P>
{
    private final Class<P> type;
    private final ToolSchema schema;
    private final Optional<ToolParameters<P>> checked;

    private RecordToolParameters(Class<P> type, boolean strict)
    {
        this.type = requireNonNull(type, "arguments is null");
        String generated = RecordArguments.schema(type);
        schema = ToolSchema.of(generated, value -> value);
        checked = strict ? Optional.of(JsonSchemaParameters.of(generated, arguments -> RecordArguments.bind(arguments.json(), type))) : Optional.empty();
    }

    /**
     * Creates strict binding with schema validation before record construction. Reflection is
     * cached by this adapter and remains an implementation choice, not an SPI requirement.
     *
     * @param <P> the record type
     * @param type the optional reflection source
     * @return the parameter codec
     */
    public static <P extends Record> RecordToolParameters<P> of(Class<P> type) { return new RecordToolParameters<>(type, true); }

    static <P extends Record> RecordToolParameters<P> legacy(Class<P> type) { return new RecordToolParameters<>(type, false); }

    @Override
    public ToolSchema schema() { return schema; }

    @Override
    public P decode(ToolArguments arguments)
    {
        requireNonNull(arguments, "arguments is null");
        return checked.isPresent() ? checked.orElseThrow().decode(arguments) : RecordArguments.bind(arguments.json(), type);
    }
}

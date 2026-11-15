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
package io.github.shiftinglabs.codej.tool.testing;

import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reusable contract for different binders of the same logical parameter type. */
public abstract class AbstractTestToolParameters<P>
{
    protected abstract ToolParameters<P> parameters();

    protected abstract List<ValidInput<P>> validInputs();

    protected abstract List<String> invalidInputs();

    protected abstract Object canonical(P input);

    public record ValidInput<P>(String json, P expected) {}

    @Test
    void stableSchemaAndBindingAgreeOnValidInputs()
    {
        ToolParameters<P> binding = parameters();
        String fingerprint = binding.schema().fingerprint();
        for (ValidInput<P> input : validInputs()) {
            P value = binding.decode(ToolArguments.of(input.json()));
            assertThat(value).isNotNull();
            assertThat(canonical(value)).isEqualTo(canonical(input.expected()));
            assertThat(canonical(binding.decode(ToolArguments.of(input.json())))).isEqualTo(canonical(value));
        }
        assertThat(binding.schema().fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void rejectsMalformedWrongAndOutOfDomainArguments()
    {
        for (String input : invalidInputs()) {
            assertThatThrownBy(() -> parameters().decode(ToolArguments.of(input))).as(input).isInstanceOf(IllegalArgumentException.class);
            // Alternate envelopes may use different limits; the binder still owns complete structural/domain validation.
            assertThatThrownBy(() -> parameters().decode(() -> input)).as(input).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

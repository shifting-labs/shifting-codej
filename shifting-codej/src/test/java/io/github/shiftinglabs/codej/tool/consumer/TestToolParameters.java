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
package io.github.shiftinglabs.codej.tool.consumer;

import io.github.shiftinglabs.codej.tool.ToolArguments;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import io.github.shiftinglabs.codej.tool.testing.AbstractTestToolParameters;
import io.github.shiftinglabs.codej.tool.testing.ToolContractFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestToolParameters
{
    @Test
    void alternativeCodecsUseDifferentRecordsBehindTheSameParameterInterface()
    {
        var first = new ToolContractFixtures.RecordParameters();
        var second = new ToolContractFixtures.StreamingParameters();
        var arguments = ToolArguments.of("{\"key\":\"ISSUE-1\"}");
        assertThat(first.decode(arguments)).isInstanceOf(ToolContractFixtures.LookupRequest.class);
        assertThat(second.decode(arguments)).isInstanceOf(ToolContractFixtures.GeneratedLookupRequest.class);
        assertThat(first.decode(arguments).key()).isEqualTo(second.decode(arguments).key());
        assertThat(first.schema().fingerprint()).isEqualTo(second.schema().fingerprint());
    }

    abstract static class Binding
            extends AbstractTestToolParameters<ToolContractFixtures.LookupInput>
    {
        @Override
        protected List<ValidInput<ToolContractFixtures.LookupInput>> validInputs()
        {
            return List.of(new ValidInput<>("{\"key\":\"ISSUE-1\"}", new ToolContractFixtures.LookupRequest("ISSUE-1")),
                    new ValidInput<>("  { \"key\" : \"ISSUE-42\" }  ", new ToolContractFixtures.LookupRequest("ISSUE-42")));
        }

        @Override
        protected List<String> invalidInputs()
        {
            return List.of("{}", "{\"key\":null}", "{\"key\":12}", "{\"key\":\"bad\"}", "{\"key\":\"ISSUE-1\",\"extra\":true}",
                    "{\"key\":[\"ISSUE-1\"]}", "{\"key\":\"ISSUE-1\",\"key\":\"ISSUE-2\"}", "null", "[]", "{} {}");
        }

        @Override
        protected Object canonical(ToolContractFixtures.LookupInput input) { return input.key(); }
    }

    @Nested
    class RecordBinding
            extends Binding
    {
        @Override
        protected ToolParameters<ToolContractFixtures.LookupInput> parameters() { return new ToolContractFixtures.RecordParameters(); }
    }

    @Nested
    class StreamingBinding
            extends Binding
    {
        @Override
        protected ToolParameters<ToolContractFixtures.LookupInput> parameters() { return new ToolContractFixtures.StreamingParameters(); }
    }
}

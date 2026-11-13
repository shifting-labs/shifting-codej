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

import io.github.shiftinglabs.codej.RecordToolParameters;
import io.github.shiftinglabs.codej.tool.ToolParameters;
import io.github.shiftinglabs.codej.tool.testing.AbstractTestToolParameters;

import java.util.List;

class TestRecordToolParametersContract
        extends AbstractTestToolParameters<TestRecordToolParametersContract.Input>
{
    record Input(int count)
    {
        Input
        {
            if (count < 0) {
                throw new IllegalArgumentException("Negative count");
            }
        }
    }

    @Override
    protected ToolParameters<Input> parameters() { return RecordToolParameters.of(Input.class); }

    @Override
    protected List<ValidInput<Input>> validInputs()
    {
        return List.of(new ValidInput<>("{\"count\":0}", new Input(0)), new ValidInput<>(" { \"count\" : 42 } ", new Input(42)));
    }

    @Override
    protected List<String> invalidInputs()
    {
        return List.of("{}", "{\"count\":-1}", "{\"count\":null}", "{\"count\":\"42\"}", "{\"count\":1,\"extra\":1}",
                "{\"count\":1,\"count\":2}", "[]", "null", "{\"count\":1} {}");
    }

    @Override
    protected Object canonical(Input input) { return input.count(); }
}

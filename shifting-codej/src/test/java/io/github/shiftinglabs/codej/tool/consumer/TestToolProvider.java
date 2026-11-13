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

import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import io.github.shiftinglabs.codej.tool.testing.AbstractTestToolProvider;
import io.github.shiftinglabs.codej.tool.testing.ToolContractFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.assertThat;

class TestToolProvider
{
    abstract static class Binding
            extends AbstractTestToolProvider<ToolContractFixtures.Configuration>
    {
        private ToolContractFixtures.Provider provider;

        protected abstract boolean alternate();

        @BeforeEach
        void setup() { provider = new ToolContractFixtures.Provider(); }

        @Override
        protected ToolProvider<ToolContractFixtures.Configuration> provider() { return provider; }

        @Override
        protected ToolContractFixtures.Configuration configuration() { return new ToolContractFixtures.Configuration(alternate(), false); }

        @Override
        protected ToolContractFixtures.Configuration invalidConfiguration() { return new ToolContractFixtures.Configuration(alternate(), true); }

        @Override
        protected int opened() { return provider.opened.get(); }

        @Override
        protected int closed() { return provider.closed.get(); }

        @Override
        protected void failClose(boolean value) { provider.failClose = value; }

        @Override
        protected void assertBorrowedServicesOpen(ToolSetContext context)
        {
            assertThat(((ToolContractFixtures.SetContext) context).supervisor.closed).isFalse();
        }
    }

    @Nested
    class ReferenceBackend
            extends Binding
    {
        @Override
        protected boolean alternate() { return false; }
    }

    @Nested
    class AlternateBackend
            extends Binding
    {
        @Override
        protected boolean alternate() { return true; }
    }
}

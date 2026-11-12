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
package io.github.shiftinglabs.codej.sdk.testing;

import io.github.shiftinglabs.codej.cli.spi.ConnectorDriver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** CLI specialization: explicit executables must not be installed, started, or required during construction. */
public abstract class AbstractTestCliConnector<C>
        extends AbstractTestConnector<C>
{
    protected abstract C configuration(Path executable);

    @Override
    protected final C configuration()
    {
        return configuration(contractDirectory().resolve("missing-cli"));
    }

    @Test
    protected void explicitCliPathIsNotResolvedDuringConstruction()
    {
        Path executable = contractDirectory().resolve("missing-cli");
        try (ConnectorDriver ignored = connector().create(configuration(executable), contractContext())) {
            assertThat(executable).doesNotExist();
        }
    }
}

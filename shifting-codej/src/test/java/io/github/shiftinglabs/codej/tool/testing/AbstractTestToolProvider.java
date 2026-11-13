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

import io.github.shiftinglabs.codej.cli.spi.tool.ToolProvider;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSet;
import io.github.shiftinglabs.codej.cli.spi.tool.ToolSetContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Installation and lifecycle contract, bound to a controlled family provider without native resources. */
public abstract class AbstractTestToolProvider<C>
{
    @TempDir
    protected Path directory;

    protected abstract ToolProvider<C> provider();

    protected abstract C configuration();

    protected abstract C invalidConfiguration();

    protected abstract int opened();

    protected abstract int closed();

    protected abstract void failClose(boolean value);

    protected void assertBorrowedServicesOpen(ToolSetContext context) {}

    protected ToolSetContext context() { return new ToolContractFixtures.SetContext(directory); }

    @Test
    void definitionsArePureAndOpenedToolsMatchTheirContracts()
    {
        var provider = provider();
        var definitions = provider.definitions(configuration());
        assertThat(provider.id()).isNotBlank();
        assertThat(opened()).isEqualTo(0);
        assertThat(closed()).isEqualTo(0);
        assertThat(directory.resolve("profile")).doesNotExist();
        var context = context();
        assertThat(context.shutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(context.session().worktree()).isEqualTo(directory);
        try (ToolSet tools = provider.open(configuration(), context)) {
            assertThat(tools.tools()).hasSize(definitions.size());
            for (int index = 0; index < definitions.size(); index++) {
                var actual = tools.tools().get(index).definition();
                var declared = definitions.get(index);
                assertThat(actual.descriptor().name()).isEqualTo(declared.descriptor().name());
                assertThat(actual.descriptor().contractVersion()).isEqualTo(declared.descriptor().contractVersion());
                assertThat(actual.parameters().schema()).isEqualTo(declared.parameters().schema());
            }
            assertThatThrownBy(() -> tools.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(closed()).isEqualTo(1);
    }

    @Test
    void failedOpenCleansOwnedResourcesAndUnverifiedCloseCanBeRetried()
    {
        var provider = provider();
        var context = context();
        assertThatThrownBy(() -> provider.open(invalidConfiguration(), context))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(opened()).isEqualTo(1);
        assertThat(closed()).isEqualTo(1);
        ToolSet tools = provider.open(configuration(), context);
        failClose(true);
        assertThatThrownBy(tools::close).isInstanceOf(IllegalStateException.class);
        assertThat(tools.tools()).hasSize(1);
        assertThat(closed()).isEqualTo(1);
        failClose(false);
        tools.close();
        tools.close();
        assertThat(closed()).isEqualTo(2);
        assertThatThrownBy(tools::tools).isInstanceOf(IllegalStateException.class);
    }
}

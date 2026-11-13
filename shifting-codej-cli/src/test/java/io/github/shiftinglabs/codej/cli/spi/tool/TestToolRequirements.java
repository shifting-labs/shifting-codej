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
package io.github.shiftinglabs.codej.cli.spi.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolRequirements
{
    @Test
    void scopesAreImmutableAndMatchPathComponents()
    {
        var builder = ToolRequirements.builder().read(Path.of("src/./main")).write(Path.of("out")).require(ToolCapability.PROCESS_EXECUTE);
        var first = builder.build();
        var second = builder.require(ToolCapability.NETWORK_ACCESS).read(Path.of("other")).build();
        assertThat(first.reads()).containsExactly(Path.of("src/main"));
        assertThat(first.writes()).containsExactly(Path.of("out"));
        assertThat(first.capabilities()).containsExactlyInAnyOrder(ToolCapability.WORKSPACE_READ, ToolCapability.WORKSPACE_WRITE, ToolCapability.PROCESS_EXECUTE);
        assertThat(second.capabilities()).contains(ToolCapability.NETWORK_ACCESS);
        assertThat(first.capabilities()).doesNotContain(ToolCapability.NETWORK_ACCESS);
        assertThat(first.permits(ToolRequirements.builder().read(Path.of("src/main/A.java")).write(Path.of("out/result")).build())).isTrue();
        assertThat(first.permits(ToolRequirements.builder().read(Path.of("src/main-old/A.java")).build())).isFalse();
        assertThat(first.permits(ToolRequirements.builder().read(Path.of("out/result")).build())).isFalse();
        assertThat(first.permits(ToolRequirements.builder().require(ToolCapability.NETWORK_ACCESS).build())).isFalse();
        assertThatThrownBy(() -> first.reads().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.writes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.capabilities().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rootScopeAndOpenCapabilitiesRequireExplicitGrants()
    {
        var root = ToolRequirements.builder().read(Path.of(".")).build();
        assertThat(root.permits(ToolRequirements.builder().read(Path.of("a/b")).build())).isTrue();
        assertThat(ToolRequirements.none().permits(root)).isFalse();
        assertThat(root.permits(ToolRequirements.none())).isTrue();
        assertThat(root.permits(ToolRequirements.builder().write(Path.of("a/b")).build())).isFalse();
        var custom = new ToolCapability("issues.read");
        var requested = ToolRequirements.builder().require(custom).build();
        assertThat(ToolRequirements.none().permits(requested)).isFalse();
        assertThat(requested.permits(requested)).isTrue();
        assertThat(new ToolCapability("workspace.read")).isEqualTo(ToolCapability.WORKSPACE_READ);
        assertThat(ToolCapability.HOST_INVOKE.id()).isEqualTo("host.invoke");
    }

    @Test
    void pathsAndCapabilityCountsAreBoundedAndTraversalFailsClosed()
    {
        for (String path : List.of("../outside", "src/../outside", "/tmp/outside")) {
            assertThatThrownBy(() -> ToolRequirements.builder().read(Path.of(path))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ToolRequirements.builder().write(Path.of(path))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ToolRequirements.builder().require(ToolCapability.WORKSPACE_READ).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolRequirements.builder().require(ToolCapability.WORKSPACE_WRITE).build()).isInstanceOf(IllegalArgumentException.class);
        for (String id : List.of("", " ", "Uppercase", "a..b", "a/../b", "x".repeat(129))) {
            assertThatThrownBy(() -> new ToolCapability(id)).isInstanceOf(IllegalArgumentException.class);
        }
        var paths = ToolRequirements.builder();
        for (int i = 0; i < 256; i++) {
            paths.read(Path.of("path" + i));
        }
        paths.read(Path.of("path0"));
        assertThat(paths.build().reads()).hasSize(256);
        assertThatThrownBy(() -> paths.read(Path.of("extra"))).isInstanceOf(IllegalArgumentException.class);
        var capabilities = ToolRequirements.builder();
        for (int i = 0; i < 256; i++) {
            capabilities.require(new ToolCapability("cap" + i));
        }
        assertThatThrownBy(() -> capabilities.require(new ToolCapability("extra"))).isInstanceOf(IllegalArgumentException.class);
    }
}

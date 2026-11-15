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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestModelCompactionMemory
{
    @Test
    void memoryIsBoundedValidatedAndRedacted()
    {
        var memory = new ModelCompactionMemory("private-boundary", "private-summary");
        assertThat(memory.throughRequestId()).isEqualTo("private-boundary");
        assertThat(memory.summary()).isEqualTo("private-summary");
        assertThat(memory.toString()).doesNotContain("private-boundary", "private-summary");
        assertThatThrownBy(() -> new ModelCompactionMemory(null, "summary")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionMemory(" ", "summary")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionMemory("id", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelCompactionMemory("id", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCompactionMemory("id", "x".repeat(ModelCompaction.MAX_SUMMARY_CHARACTERS + 1))).isInstanceOf(IllegalArgumentException.class);
    }
}

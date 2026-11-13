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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodingTools
{
    @Test
    void defaultsAreExplicitAndValuesAreImmutable()
    {
        CodingTools value = CodingTools.defaults();
        assertThat(value.maximumFileBytes()).isEqualTo(1024 * 1024);
        assertThat(value.maximumSearchBytes()).isEqualTo(64 * 1024 * 1024);
        assertThat(value.maximumEntries()).isEqualTo(100000);
        assertThat(value.maximumCommandSeconds()).isEqualTo(30);
        assertThat(value.maximumOutputBytes()).isEqualTo(8192);
        assertThat(value).isEqualTo(CodingTools.defaults());
    }

    @Test
    void rejectsInvalidOrUnboundedLimits()
    {
        for (int[] limits : new int[][] {{0, 1024, 1, 1, 1}, {1024, 1, 1, 1, 1}, {1, 1, 0, 1, 1}, {1, 1, 1, 0, 1}, {1, 1, 1, 1, 0},
                {16777217, 16777217, 1, 1, 1}, {1, 1073741825, 1, 1, 1}, {1, 1, 1000001, 1, 1}, {1, 1, 1, 301, 1}, {1, 1, 1, 1, 65537}}) {
            assertThatThrownBy(() -> new CodingTools(limits[0], limits[1], limits[2], limits[3], limits[4])).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

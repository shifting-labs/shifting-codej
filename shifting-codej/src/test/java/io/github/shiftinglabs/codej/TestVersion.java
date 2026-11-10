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

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

final class TestVersion
{
    /** A release is the Codex release it runs, then its own number: 0.164.0-1, 0.164.0-2, 0.164.0-1-rc1. */
    @Test
    void theVersionStartsWithTheCodexReleaseItRuns()
    {
        String version = System.getProperty("codej.version");

        assertThat(version).as("codej.version from the build").isNotNull();
        assertThat(version).matches(Pattern.quote(Codex.VERSION) + "-[1-9][0-9]*(-rc[1-9][0-9]*)?(-SNAPSHOT)?");
    }
}

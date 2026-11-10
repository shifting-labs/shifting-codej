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
package io.github.shiftinglabs.codej.sdk.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSessionSweeps
{
    @TempDir
    Path home;

    @Test
    void agentsOnOneHomeShareASweepThatKeepsTheShortestTime()
            throws Exception
    {
        List<Instant> cutoffs = new ArrayList<>();
        SessionSweeps.Pruner pruner = (_, deleteBefore) -> {
            cutoffs.add(deleteBefore);
            return 0;
        };
        AutoCloseable month = SessionSweeps.keep(home, Duration.ofDays(30), pruner);
        AutoCloseable week = SessionSweeps.keep(home, Duration.ofDays(7), pruner);

        Instant before = Instant.now();
        SessionSweeps.sweep(home);

        assertThat(cutoffs).singleElement().satisfies(cutoff ->
                assertThat(cutoff).isBetween(before.minus(Duration.ofDays(7)), Instant.now().minus(Duration.ofDays(7))));

        week.close();
        week.close();
        assertThat(SessionSweeps.keeps(home)).containsExactly(Duration.ofDays(30));
        month.close();
        assertThat(SessionSweeps.keeps(home)).isEmpty();
        SessionSweeps.sweep(home);
        assertThat(cutoffs).hasSize(1);
    }

    @Test
    void aFailedSweepIsTriedAgainLater()
            throws Exception
    {
        try (AutoCloseable _ = SessionSweeps.keep(home, Duration.ofDays(1), (_, _) -> {
            throw new IOException("unreadable");
        })) {
            SessionSweeps.sweep(home);
            assertThat(SessionSweeps.keeps(home)).containsExactly(Duration.ofDays(1));
        }
    }

    @Test
    void refusesATimeThatIsNotPositive()
    {
        assertThatThrownBy(() -> SessionSweeps.keep(home, Duration.ZERO, (_, _) -> 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

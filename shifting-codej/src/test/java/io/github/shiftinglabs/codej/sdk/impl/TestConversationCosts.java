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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TestConversationCosts
{
    @TempDir
    private Path folder;

    @Test
    void aTurnCostsWhatItsConversationsTotalGrewBy()
    {
        ConversationCosts costs = new ConversationCosts(folder);
        assertThat(costs.turnCost("a", new BigDecimal("0.30"))).contains(new BigDecimal("0.30"));
        assertThat(costs.turnCost("b", new BigDecimal("0.10"))).contains(new BigDecimal("0.10"));
        // A later program reads the totals the earlier one saved.
        assertThat(new ConversationCosts(folder).turnCost("a", new BigDecimal("0.45"))).contains(new BigDecimal("0.15"));
    }

    @Test
    void aTotalThatFellLeavesTheTurnUnknownAndCountsOnFromIt()
    {
        ConversationCosts costs = new ConversationCosts(folder);
        costs.turnCost("a", new BigDecimal("0.30"));
        assertThat(costs.turnCost("a", new BigDecimal("0.05"))).isEmpty();
        assertThat(costs.turnCost("a", new BigDecimal("0.08"))).contains(new BigDecimal("0.03"));
        // A hair below the last total is rounding, not a new count.
        assertThat(costs.turnCost("a", new BigDecimal("0.0799999"))).contains(BigDecimal.ZERO);
    }

    @Test
    void anUnreadableTotalLeavesTheTurnUnknown()
            throws IOException
    {
        ConversationCosts costs = new ConversationCosts(folder);
        costs.turnCost("a", new BigDecimal("0.30"));
        try (Stream<Path> files = Files.list(folder)) {
            Files.writeString(files.findFirst().orElseThrow(), "not a number");
        }
        assertThat(costs.turnCost("a", new BigDecimal("0.40"))).isEmpty();
        assertThat(costs.turnCost("a", new BigDecimal("0.50"))).contains(new BigDecimal("0.10"));
    }
}

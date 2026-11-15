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

import io.github.shiftinglabs.codej.cli.spi.context.ContextBudget;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestModelContextBudget
{
    @Test
    void assessmentsReserveOutputAndMarginWithoutOverflowAndDeclareAccuracy()
    {
        var value = new ModelContextAssessment(100, 70, 20, ModelContextAssessment.Accuracy.ESTIMATED);
        assertThat(value.contextWindowTokens()).isEqualTo(100);
        assertThat(value.inputTokens()).isEqualTo(70);
        assertThat(value.maximumOutputTokens()).isEqualTo(20);
        assertThat(value.accuracy()).isEqualTo(ModelContextAssessment.Accuracy.ESTIMATED);
        assertThat(value.fits(20, 10)).isTrue();
        assertThat(value.fits(20, 11)).isFalse();
        assertThat(value.fits(21, 0)).isFalse();
        assertThat(value.fits(1, Long.MAX_VALUE)).isFalse();
        assertThat(new ModelContextAssessment(Long.MAX_VALUE, Long.MAX_VALUE, 1, ModelContextAssessment.Accuracy.EXACT).fits(1, 0)).isFalse();
        assertThatThrownBy(() -> new ModelContextAssessment(0, 0, 1, ModelContextAssessment.Accuracy.EXACT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextAssessment(10, -1, 1, ModelContextAssessment.Accuracy.EXACT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextAssessment(10, 0, 11, ModelContextAssessment.Accuracy.EXACT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextAssessment(10, 0, 0, ModelContextAssessment.Accuracy.EXACT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextAssessment(10, 0, 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> value.fits(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.fits(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ContextBudget(12, true).safetyMarginTokens()).isEqualTo(12);
        assertThat(new ContextBudget(12, true).requireKnownSize()).isTrue();
        assertThatThrownBy(() -> new ContextBudget(-1, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectedInputIsBoundedTypedAndPrivate()
    {
        var value = new ModelContextInput("fixture", "model", "{\"private\":\"content\"}", 16, Optional.of(Instant.MAX));
        assertThat(value.providerId()).isEqualTo("fixture");
        assertThat(value.model()).isEqualTo("model");
        assertThat(value.serializedInput()).contains("content");
        assertThat(value.maximumOutputTokens()).isEqualTo(16);
        assertThat(value.deadline()).contains(Instant.MAX);
        assertThat(value.toString()).doesNotContain("content", "model");
        assertThatThrownBy(() -> new ModelContextInput(null, "model", "{}", 1, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelContextInput("bad/provider", "model", "{}", 1, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextInput("fixture", " ", "{}", 1, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextInput("fixture", "model", null, 1, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelContextInput("fixture", "model", " ", 1, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextInput("fixture", "model", "x".repeat(ModelContextInput.MAX_INPUT_CHARACTERS + 1), 1, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextInput("fixture", "model", "{}", 0, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextInput("fixture", "model", "{}", 1, null)).isInstanceOf(NullPointerException.class);
    }
}

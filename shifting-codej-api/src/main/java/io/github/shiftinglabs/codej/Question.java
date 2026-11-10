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

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Questions the agent asks the user during a turn, sent to the session's {@link QuestionHandler}.
 *
 * @param source the agent that asks
 * @param items one or more questions, asked together
 */
public record Question(Source source, List<Item> items)
{
    /**
     * Creates a question; copies {@code items}.
     *
     * @throws IllegalArgumentException when {@code items} is empty
     */
    public Question
    {
        requireNonNull(source, "source is null");
        items = ImmutableList.copyOf(items);
        checkArgument(!items.isEmpty(), "items is empty");
    }

    /**
     * One question.
     *
     * @param id the key for this question's answer
     * @param header a short label, such as {@code Database}
     * @param text the question
     * @param options suggested answers; the user may also give their own
     * @param multiSelect whether several options may be chosen
     */
    public record Item(String id, String header, String text, List<Option> options, boolean multiSelect)
    {
        /** Creates a question item; copies {@code options}. */
        public Item
        {
            options = ImmutableList.copyOf(options);
        }
    }

    /**
     * A suggested answer.
     *
     * @param label the answer, as the agent will read it
     * @param description what choosing it means
     */
    public record Option(String label, String description) {}
}

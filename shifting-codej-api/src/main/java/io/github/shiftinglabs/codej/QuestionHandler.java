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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Answers a session's {@link Question}s.
 *
 * <p>Runs on the agent's callback executor. A question left out of the answers, a stage that does not
 * complete within {@link SessionOptions#approvalTimeout()}, or a failed stage tells the agent the user
 * did not answer.
 *
 * <p>When the question ends without the handler's answers, because the wait ran out or the provider
 * withdrew it, the stage the handler returned is cancelled, so the user need not answer it any more.
 */
@FunctionalInterface
public interface QuestionHandler
{
    /**
     * Answers one set of questions.
     *
     * @param question what the agent asks
     * @return answers keyed by {@link Question.Item#id()}: the labels of the chosen options, or the
     *         user's own words as one entry
     */
    CompletionStage<Map<String, List<String>>> answer(Question question);
}

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

import io.github.shiftinglabs.codej.Access;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.ClaudeApi;
import io.github.shiftinglabs.codej.DeepSeekApi;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPlan;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.model.ModelInput;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Candidate-only public usage: explicitly selected summaries share the engine's inference lane. */
public final class ContextSummarizationConsumer
{
    public static void main(String[] args)
            throws Exception
    {
        for (boolean claude : new boolean[] {true, false}) {
            Path root = Path.of(args[0]).resolve(claude ? "claude" : "deepseek");
            Files.createDirectories(root);
            try (var fixture = new ContextCompactionConsumer.Fixture(claude, root)) {
                AtomicInteger proposals = new AtomicInteger();
                ContextSummarizationPolicy policy = context -> {
                    if (context.history().exchanges().size() < 2) {
                        return Optional.empty();
                    }
                    require(((ModelInput.UserMessage) context.history().exchanges().getFirst().input()).text().contains("original audit"), "Missing audit prefix");
                    proposals.incrementAndGet();
                    return Optional.of(new ContextSummarizationPlan(1, 64));
                };
                var options = SessionOptions.in(root).access(Access.TOOLS_ONLY).build();
                SessionRef saved;
                try (Agent agent = agent(fixture, policy); Session session = agent.startSession(options)) {
                    ContextCompactionConsumer.completed(session, "original audit " + "x".repeat(8000));
                    ContextCompactionConsumer.completed(session, "recent context " + "r".repeat(2000));
                    ContextCompactionConsumer.completed(session, "fresh input");
                    saved = session.ref().orElseThrow();
                }
                try (Agent agent = agent(fixture, policy); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "after restart");
                }
                try (Agent agent = fixture.agent(Optional.empty()); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "without policy");
                }
                require(proposals.get() == 2 && fixture.requests.get() == 7, "Unexpected summary inference count");
                require(fixture.failure.get() == null, "Wire check failed");
                require(fixture.lastBody.get().contains("Conversation summary") && fixture.lastBody.get().contains("fresh input")
                        && !fixture.lastBody.get().contains("original audit") && !fixture.lastBody.get().contains("recent context"), "Wrong active replay");
            }
        }
        System.out.println("PASS: budgeted Claude/DeepSeek LLM summaries through public facades, repeated compaction and durable resume; JDK + jar only");
    }

    private static Agent agent(ContextCompactionConsumer.Fixture f, ContextSummarizationPolicy policy)
    {
        URI endpoint = URI.create("http://127.0.0.1:" + f.server.getAddress().getPort());
        if (f.claude) {
            return ClaudeApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                    .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                    .contextSummarization(policy).build();
        }
        return DeepSeekApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                .contextSummarization(policy).build();
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

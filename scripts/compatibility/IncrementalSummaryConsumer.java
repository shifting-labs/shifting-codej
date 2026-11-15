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

/** Candidate-only public usage: bounded chunks reuse committed memory across growing histories. */
public final class IncrementalSummaryConsumer
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
                    require(((ModelInput.UserMessage) context.history().exchanges().getFirst().input()).text().contains("old-one"), "Missing audit prefix");
                    proposals.incrementAndGet();
                    return Optional.of(new ContextSummarizationPlan(1, 64));
                };
                var options = SessionOptions.in(root).access(Access.TOOLS_ONLY).build();
                SessionRef saved;
                try (Agent agent = fixture.agent(Optional.empty()); Session session = agent.startSession(options)) {
                    ContextCompactionConsumer.completed(session, "old-one " + "x".repeat(1200));
                    ContextCompactionConsumer.completed(session, "old-two " + "x".repeat(1200));
                    ContextCompactionConsumer.completed(session, "kept " + "x".repeat(1200));
                    saved = session.ref().orElseThrow();
                }
                try (Agent agent = agent(fixture, policy); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "fresh input");
                }
                try (Agent agent = agent(fixture, policy); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "after restart");
                }
                try (Agent agent = fixture.agent(Optional.empty()); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "without policy");
                }
                require(proposals.get() == 2 && fixture.requests.get() == 9, "Unexpected summary inference count");
                require(fixture.failure.get() == null, "Wire check failed");
                require(fixture.lastBody.get().contains("Conversation summary") && fixture.lastBody.get().contains("fresh input")
                        && !fixture.lastBody.get().contains("old-one") && !fixture.lastBody.get().contains("old-two")
                        && !fixture.lastBody.get().contains("kept"), "Wrong active replay");
                require(fixture.requestBodies.get(3).contains("old-one") && !fixture.requestBodies.get(3).contains("old-two"), "First chunk was not bounded");
                require(fixture.requestBodies.get(4).contains("old-two") && !fixture.requestBodies.get(4).contains("old-one"), "Prefix was summarized again");
                require(fixture.requestBodies.get(6).contains("kept") && !fixture.requestBodies.get(6).contains("old-one")
                        && !fixture.requestBodies.get(6).contains("old-two"), "Memory was not recovered on resume");
            }
        }
        System.out.println("PASS: bounded incremental Claude/DeepSeek summaries, growing history and durable memory; public facades; JDK + jar only");
    }

    private static Agent agent(ContextCompactionConsumer.Fixture f, ContextSummarizationPolicy policy)
    {
        URI endpoint = URI.create("http://127.0.0.1:" + f.server.getAddress().getPort());
        if (f.claude) {
            return ClaudeApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                    .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                    .contextSummarization(policy).maximumSummaryInputCharacters(2000).build();
        }
        return DeepSeekApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                .contextSummarization(policy).maximumSummaryInputCharacters(2000).build();
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

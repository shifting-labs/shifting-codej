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
import io.github.shiftinglabs.codej.cli.spi.retention.AuditRetentionPolicy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Candidate-only public usage: opt-in audit pruning keeps resumable summary boundaries. */
public final class AuditRetentionConsumer
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
                    require(context.history().complete() || context.history().summarizedThroughRequestId().isPresent(), "Missing retained prefix metadata");
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
                    require(checkpoint(fixture).contains("\"version\":3") && !checkpoint(fixture).contains("old-one")
                            && !checkpoint(fixture).contains("old-two"), "Covered audit data was not pruned");
                }
                try (Agent agent = agent(fixture, policy); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "after restart");
                    require(!checkpoint(fixture).contains("kept"), "Old audit suffix was not pruned after restart");
                    Files.writeString(root.getParent().resolve(claude ? "claude-retained-checkpoint.json" : "deepseek-retained-checkpoint.json"), checkpoint(fixture));
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
        System.out.println("PASS: Claude/DeepSeek audit retention, format-3 checkpoints and resumable incremental memory; public facades; JDK + jar only");
    }

    private static Agent agent(ContextCompactionConsumer.Fixture f, ContextSummarizationPolicy policy)
    {
        URI endpoint = URI.create("http://127.0.0.1:" + f.server.getAddress().getPort());
        if (f.claude) {
            return ClaudeApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                    .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                    .contextSummarization(policy).maximumSummaryInputCharacters(2000).auditRetention(AuditRetentionPolicy.keepLatest(0, 0)).build();
        }
        return DeepSeekApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                .contextSummarization(policy).maximumSummaryInputCharacters(2000).auditRetention(AuditRetentionPolicy.keepLatest(0, 0)).build();
    }

    private static String checkpoint(ContextCompactionConsumer.Fixture fixture)
            throws Exception
    {
        try (var files = Files.walk(fixture.root.resolve("profile"))) {
            return Files.readString(files.filter(path -> path.getFileName().toString().equals("checkpoint.json")).findFirst().orElseThrow());
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

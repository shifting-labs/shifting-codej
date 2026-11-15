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
import io.github.shiftinglabs.codej.cli.spi.context.ContextBudget;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPlan;
import io.github.shiftinglabs.codej.cli.spi.context.ContextSummarizationPolicy;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextAssessment;
import io.github.shiftinglabs.codej.cli.spi.model.ModelContextSizer;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Candidate-only fixture; character counts simulate token sizing and are not a production tokenizer. */
public final class ContextBudgetConsumer
{
    public static void main(String[] args)
            throws Exception
    {
        for (boolean claude : new boolean[] {true, false}) {
            Path root = Path.of(args[0]).resolve(claude ? "claude" : "deepseek");
            Files.createDirectories(root);
            try (var fixture = new ContextCompactionConsumer.Fixture(claude, root)) {
                var options = SessionOptions.in(root).access(Access.TOOLS_ONLY).build();
                SessionRef saved;
                try (Agent agent = fixture.agent(Optional.empty()); Session session = agent.startSession(options)) {
                    ContextCompactionConsumer.completed(session, "old-audit " + "x".repeat(1200));
                    ContextCompactionConsumer.completed(session, "kept " + "y".repeat(1200));
                    saved = session.ref().orElseThrow();
                }
                AtomicInteger sizingCalls = new AtomicInteger();
                ModelContextSizer sizer = input -> {
                    sizingCalls.incrementAndGet();
                    require(!input.serializedInput().contains("sk-context-packaged"), "Credential entered sizing input");
                    return Optional.of(new ModelContextAssessment(2500, input.serializedInput().length(), 64, ModelContextAssessment.Accuracy.ESTIMATED));
                };
                try (Agent agent = agent(fixture, sizer); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "fresh input");
                    require(fixture.lastBody.get().contains("Conversation summary") && !fixture.lastBody.get().contains("old-audit"), "Pressure did not compact active context");
                    require(fixture.lastBody.get().contains("\"max_tokens\":64"), "Provider output cap was ignored");
                }
                try (Agent agent = agent(fixture, sizer); Session session = agent.resumeSession(saved, options)) {
                    ContextCompactionConsumer.completed(session, "after restart");
                }
                require(fixture.failure.get() == null && fixture.requests.get() == 5 && sizingCalls.get() == 4, "Unexpected inference or sizing count");
            }
        }
        for (boolean claude : new boolean[] {true, false}) {
            builtInSizing(claude, Path.of(args[0]).resolve(claude ? "claude-builtin" : "deepseek-builtin"));
        }
        System.out.println("PASS: custom and built-in context sizing, pressured compaction, output caps and resume; JDK + jar only");
    }

    private static void builtInSizing(boolean claude, Path root)
            throws Exception
    {
        Files.createDirectories(root);
        try (var fixture = new ContextCompactionConsumer.Fixture(claude, root, claude ? "claude-sonnet-4-5" : "deepseek-flash")) {
            URI endpoint = URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort());
            var budget = new ContextBudget(1024, true);
            var options = SessionOptions.in(root).access(Access.TOOLS_ONLY).build();
            try (Agent agent = claude
                    ? ClaudeApi.builder().model("claude-sonnet-4-5").apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                            .profileDirectory(root.resolve("profile")).stateDirectory(root.resolve("state"))
                            .maximumOutputTokens(500000).contextBudget(budget).build()
                    : DeepSeekApi.builder().model("deepseek-flash").apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                            .profileDirectory(root.resolve("profile")).stateDirectory(root.resolve("state"))
                            .maximumOutputTokens(500000).contextBudget(budget).build();
                    Session session = agent.startSession(options)) {
                ContextCompactionConsumer.completed(session, "Size this without a custom callback 世界 😀");
                require(fixture.lastBody.get().contains("\"max_tokens\":" + (claude ? 64000 : 131072)), "Built-in output cap missing");
            }
            require(fixture.failure.get() == null && fixture.requests.get() == 1, "Built-in sizing added inference HTTP");
        }
    }

    private static Agent agent(ContextCompactionConsumer.Fixture f, ModelContextSizer sizer)
    {
        URI endpoint = URI.create("http://127.0.0.1:" + f.server.getAddress().getPort());
        ContextSummarizationPolicy policy = context -> context.contextAssessment().isPresent()
                ? Optional.of(new ContextSummarizationPlan(1, 64)) : Optional.empty();
        if (f.claude) {
            return ClaudeApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                    .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                    .contextBudget(new ContextBudget(40, true)).contextSizer(sizer).contextSummarization(policy).build();
        }
        return DeepSeekApi.builder().model(f.model).apiKey(() -> "sk-context-packaged").baseUrl(endpoint)
                .profileDirectory(f.root.resolve("profile")).stateDirectory(f.root.resolve("state")).requestTimeout(Duration.ofSeconds(5))
                .contextBudget(new ContextBudget(40, true)).contextSizer(sizer).contextSummarization(policy).build();
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

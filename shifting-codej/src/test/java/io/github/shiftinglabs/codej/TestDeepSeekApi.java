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

import io.github.shiftinglabs.codej.sdk.testing.DeepSeekHttpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekApi
{
    @TempDir
    private Path directory;
    private DeepSeekHttpFixture http;

    @BeforeEach
    void setup()
            throws Exception
    {
        http = new DeepSeekHttpFixture();
    }

    @AfterEach
    void cleanup() { http.close(); }

    private DeepSeekApi.Builder builder()
    {
        return DeepSeekApi.builder().model(DeepSeekHttpFixture.MODEL).apiKey(() -> "sk-facade-test").baseUrl(http.baseUrl())
                .stateDirectory(directory.resolve("state")).profileDirectory(directory.resolve("profile"))
                .requestTimeout(Duration.ofSeconds(5)).maximumOutputTokens(128);
    }

    private SessionOptions options()
    {
        return SessionOptions.in(directory).access(Access.TOOLS_ONLY).build();
    }

    @Test
    void exposesMetadataWithoutInferenceAndStartsNoCli()
    {
        AtomicInteger credentials = new AtomicInteger();
        try (Agent agent = builder().apiKey(() -> {
            credentials.incrementAndGet();
            return "sk-facade-test";
        }).build()) {
            assertThat(credentials).hasValue(0);
            assertThat(http.requests()).isEmpty();
            assertThat(agent.status().ready()).isTrue();
            assertThat(agent.models()).extracting(Model::id).containsExactly(DeepSeekHttpFixture.MODEL);
            assertThat(agent.limits().message()).isPresent();
            assertThat(agent.unconfirmedDirectories()).isEmpty();
            assertThat(http.requests()).allMatch(request -> request.method().equals("GET"));
        }
    }

    @Test
    void usesExistingSessionApiAndResumesPrivateReplayAcrossAgents()
            throws Exception
    {
        SessionRef saved;
        try (Agent agent = builder().build(); Session session = agent.startSession(options())) {
            assertThat(session.run(Input.text("Hello"))).isInstanceOfSatisfying(TurnResult.Completed.class,
                    result -> assertThat(result.finalText()).isEqualTo("Hello world"));
            saved = session.ref().orElseThrow();
            assertThat(saved.provider()).isEqualTo("deepseek-api");
            assertThat(session.keepsProcess()).isFalse();
        }
        try (Agent agent = builder().build(); Session session = agent.resumeSession(SessionRef.parse(saved.asString()), options())) {
            assertThat(session.run(Input.text("Continue"))).isInstanceOf(TurnResult.Completed.class);
        }
        var last = http.requests().stream().filter(request -> request.method().equals("POST")).toList().getLast();
        assertThat(last.body().path("messages").findValuesAsText("role")).containsSubsequence("user", "assistant", "user");
        assertThat(last.body().toString()).contains("Hello world", "Continue");
        try (var files = Files.walk(directory.resolve("profile"))) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(path)).doesNotContain("sk-facade-test");
            }
        }
    }

    @Test
    void runsHostToolsOnTheBorrowedCallbackExecutor()
            throws Exception
    {
        AtomicReference<String> thread = new AtomicReference<>();
        try (ExecutorService callbacks = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("host-callback").factory())) {
            Tool lookup = Tool.of("contract_lookup", "Look up a contract", Lookup.class, input -> {
                thread.set(Thread.currentThread().getName());
                return input.key() + " sk-facade-test";
            });
            SessionOptions options = SessionOptions.in(directory).access(Access.TOOLS_ONLY).tool(lookup)
                    .approvals(approval -> java.util.concurrent.CompletableFuture.completedStage(Decision.ALLOW_ONCE)).build();
            try (Agent agent = builder().callbackExecutor(callbacks).build(); Session session = agent.startSession(options)) {
                assertThat(session.run(Input.text("Lookup"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(thread.get()).isEqualTo("host-callback");
                assertThat(http.requests().getLast().body().toString()).doesNotContain("sk-facade-test");
                assertThat(http.requests().stream().filter(request -> request.method().equals("POST")).toList()).hasSize(2);
            }
            assertThat(callbacks.isShutdown()).isFalse();
        }
    }

    @Test
    void cancellationKeepsTheWriterUntilAnExternalToolActuallyReturns()
            throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService callbacks = Executors.newSingleThreadExecutor()) {
            Tool lookup = Tool.of("contract_lookup", "Look up a contract", Lookup.class, input -> {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("Handler was not released");
                    }
                }
                catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                return input.key();
            });
            var options = SessionOptions.in(directory).access(Access.TOOLS_ONLY).tool(lookup)
                    .approvals(approval -> java.util.concurrent.CompletableFuture.completedStage(Decision.ALLOW_ONCE)).build();
            try (Agent agent = builder().callbackExecutor(callbacks).build(); Session session = agent.startSession(options)) {
                Turn turn = session.start(Input.text("Lookup"), event -> {});
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                turn.interrupt();
                assertThatThrownBy(() -> session.start(Input.text("More"), event -> {})).isInstanceOf(IllegalStateException.class);
                release.countDown();
                assertThat(turn.await()).isInstanceOf(TurnResult.Interrupted.class);
            }
            finally {
                release.countDown();
            }
        }
    }

    @Test
    void retainsExistingSessionDefaultsAndRejectsUnsupportedFilesystemAccess()
    {
        try (Agent agent = builder().build()) {
            assertThatThrownBy(() -> agent.startSession(SessionOptions.in(directory).build())).isInstanceOf(AgentException.class);
            assertThat(http.requests()).noneMatch(request -> request.method().equals("POST"));
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void publicCodingOptionReachesTheSharedEngine()
            throws Exception
    {
        Files.writeString(directory.resolve("source.txt"), "shared-engine-content");
        SessionOptions options = SessionOptions.in(directory).access(Access.READ_ONLY)
                .approvals(approval -> java.util.concurrent.CompletableFuture.completedStage(Decision.ALLOW_ONCE)).build();
        try (Agent agent = builder().codingTools(CodingTools.defaults()).build()) {
            assertThat(agent.status().ready()).isTrue();
            String arguments = DeepSeekHttpFixture.JSON.writeValueAsString(new FilePath("source.txt"));
            String tool = DeepSeekHttpFixture.JSON.writeValueAsString(new ApiToolUse(0, "read-1", "function", new ApiFunction("read_file", arguments)));
            http.reply(200, "data: {\"id\":\"coding-1\",\"object\":\"chat.completion.chunk\",\"model\":\"deepseek-contract\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[" + tool
                    + "]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n");
            try (Session session = agent.startSession(options)) {
                assertThat(session.run(Input.text("Read source"))).isInstanceOf(TurnResult.Completed.class);
                assertThat(http.requests().stream().filter(request -> request.method().equals("POST")).toList()).hasSize(2);
                assertThat(http.requests().getLast().body().toString()).contains("shared-engine-content", "sha256");
            }
        }
    }

    private record FilePath(String path) {}

    private record ApiFunction(String name, String arguments) {}

    private record ApiToolUse(int index, String id, String type, ApiFunction function) {}

    @Test
    void codingToolsAreOptInAndBuildStartsNoCommandsOrInference()
    {
        try (Agent agent = builder().codingTools(CodingTools.defaults()).build()) {
            assertThat(agent).isNotNull();
            assertThat(http.requests()).isEmpty();
        }
        assertThatThrownBy(() -> builder().codingTools(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void validatesRequiredSettingsAndSafeEndpoints()
    {
        assertThatThrownBy(() -> DeepSeekApi.builder().build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder().model(" ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().maximumOutputTokens(0).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().requestTimeout(Duration.ZERO).build()).isInstanceOf(IllegalArgumentException.class);
        for (String url : new String[] {"http://example.com", "https://key@example.com", "https://example.com?key=secret"}) {
            assertThatThrownBy(() -> builder().baseUrl(URI.create(url)).build()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(http.requests()).isEmpty();
    }

    public record Lookup(String key) {}
}

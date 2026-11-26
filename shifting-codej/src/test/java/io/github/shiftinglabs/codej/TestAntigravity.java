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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the public Antigravity API through a local ACP executable and the real process supervisor. */
@EnabledOnOs(OS.MAC)
class TestAntigravity
{
    @TempDir
    private Path folder;

    @Test
    void distinguishesAMissingExecutableAnOldVersionAndAMissingLogin()
            throws IOException
    {
        Path state = folder.resolve("state");
        try (Agent missing = Antigravity.builder().executable(folder.resolve("missing")).stateDirectory(state).build()) {
            assertThat(missing.status().readiness()).isEqualTo(AgentStatus.Readiness.CLI_MISSING);
        }
        Path old = Files.writeString(folder.resolve("old-agy"), "#!/bin/sh\necho 1.4.9\n");
        Files.setPosixFilePermissions(old, PosixFilePermissions.fromString("rwx------"));
        try (Agent unsupported = Antigravity.builder().executable(old).stateDirectory(state).build()) {
            AgentStatus status = unsupported.status();
            assertThat(status.readiness()).as(status.detail()).isEqualTo(AgentStatus.Readiness.UNSUPPORTED_VERSION);
            assertThat(status.version()).contains("1.4.9");
        }
        Path agy = fakeCli();
        Path profile = folder.resolve("Google's profile");
        try (Agent unsigned = Antigravity.builder().executable(agy).configDir(profile).stateDirectory(state).build()) {
            AgentStatus status = unsigned.status();
            assertThat(status.readiness()).as(status.detail()).isEqualTo(AgentStatus.Readiness.SIGN_IN_REQUIRED);
            assertThat(status.signInCommand()).hasValueSatisfying(command -> assertThat(command)
                    .contains("HOME=", agy.toString(), "acp --login"));
        }
        String key = "invalid-fixture-key";
        try (Agent rejected = Antigravity.builder().executable(agy).configDir(profile).stateDirectory(state).apiKey(() -> key).build()) {
            AgentStatus status = rejected.status();
            assertThat(status.ready()).isFalse();
            assertThat(status.detail()).doesNotContain(key);
            assertThat(status.signInCommand()).isEmpty();
        }
        try (Agent noReader = Antigravity.builder().executable(agy).stateDirectory(state).apiKey(() -> "missing-reader").build()) {
            assertThat(noReader.status().ready()).isFalse();
            assertThatThrownBy(() -> noReader.startSession(SessionOptions.in(folder).build()))
                    .isInstanceOf(AgentException.class).hasMessageContaining("read-only agent");
        }
    }

    @Test
    void streamsRedactedTextThenResumesWithTheRotatedKeyAndStopsOnCancellation()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        SessionOptions options = SessionOptions.in(checkout).build();
        AtomicReference<String> key = new AtomicReference<>("fixture-key-first");
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                .apiKey(key::get).build()) {
            AgentStatus status = agent.status();
            assertThat(status.readiness()).as(status.detail()).isEqualTo(AgentStatus.Readiness.READY);
            assertThat(status.version()).contains("1.5.10");
            assertThat(agent.models()).extracting(Model::id).contains("gemini-test");

            String reference;
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            try (Session session = agent.startSession(options)) {
                TurnResult result = session.start(Input.text("first"), TurnOptions.defaults().withModel("gemini-test").withEffort("medium"), events::add)
                        .result().toCompletableFuture().get(20, SECONDS);
                assertThat(result).isInstanceOfSatisfying(TurnResult.Completed.class, completed -> {
                    assertThat(completed.finalText()).isEqualTo("Hello [REDACTED]");
                    assertThat(completed.usage().inputTokens()).isEqualTo(10);
                    assertThat(completed.usage().cachedInputTokens()).isEqualTo(4);
                    assertThat(completed.usage().outputTokens()).isEqualTo(7);
                });
                assertThat(events.getFirst()).isInstanceOf(AgentEvent.Started.class);
                assertThat(events.stream().filter(AgentEvent.MessageDelta.class::isInstance).map(AgentEvent.MessageDelta.class::cast)
                        .map(AgentEvent.MessageDelta::delta)).containsExactly("Hello ", "[REDACTED]");
                assertThat(events.stream().filter(AgentEvent.ToolCall.class::isInstance).map(AgentEvent.ToolCall.class::cast).toList())
                        .allSatisfy(tool -> assertThat(tool.tool()).isEqualTo("Read file"))
                        .extracting(AgentEvent.ToolCall::activity)
                        .containsExactly(AgentEvent.Activity.STARTED, AgentEvent.Activity.PROGRESS, AgentEvent.Activity.COMPLETED);
                assertThat(events.toString()).doesNotContain(key.get());
                reference = session.ref().orElseThrow().asString();
            }

            SessionRef saved = SessionRef.parse(reference);
            SessionRef foreign = new SessionRef(saved.provider(), "1:98c18918-6af2-4dbf-b59a-e95e0df178d5");
            SessionOptions otherCheckout = SessionOptions.in(Files.createDirectory(folder.resolve("other-checkout"))).build();
            assertThatThrownBy(() -> agent.resumeSession(foreign, options)).isInstanceOf(AgentException.class);
            assertThatThrownBy(() -> agent.resumeSession(saved, otherCheckout)).isInstanceOf(AgentException.class);

            key.set("fixture-key-second");
            try (Session resumed = agent.resumeSession(saved, options)) {
                CompletableFuture<Long> processId = new CompletableFuture<>();
                Turn pending = resumed.start(Input.text("hold"), event -> {
                    if (event instanceof AgentEvent.MessageDelta delta) {
                        processId.complete(Long.parseLong(delta.delta()));
                    }
                });
                long pid = processId.get(20, SECONDS);
                assertThat(ProcessHandle.of(pid)).isPresent();
                assertThat(pending.interrupt().toCompletableFuture().get(20, SECONDS)).isInstanceOf(TurnResult.Interrupted.class);
                assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
            }
            assertThat(agent.unconfirmedDirectories()).isEmpty();
            assertThat(folder.resolve("state/processes/intents")).isEmptyDirectory();
        }
    }

    @Test
    void completesTheTurnWhenUsageExceedsTheSupportedRange()
            throws IOException, InterruptedException
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state")).apiKey(() -> "fixture-key-first").build();
                Session session = agent.startSession(SessionOptions.in(checkout).build())) {
            assertThat(session.run(Input.text("invalid-usage"))).isInstanceOfSatisfying(TurnResult.Completed.class, completed -> {
                assertThat(completed.finalText()).isEqualTo("Completed with invalid usage");
                assertThat(completed.usage().inputTokens()).isZero();
                assertThat(completed.usage().outputTokens()).isZero();
            });
        }
    }

    @Test
    void rejectsUnsupportedScopeBeforeStartingAnAcpSession()
            throws IOException
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                .apiKey(() -> "fixture-key-first").build()) {
            assertThatThrownBy(() -> agent.startSession(SessionOptions.in(checkout).allowNetwork().build()))
                    .isInstanceOf(AgentException.class).hasMessageContaining("does not support the requested permission scope");
            assertThat(agent.unconfirmedDirectories()).isEmpty();
            assertThat(folder.resolve("state/processes/intents")).isEmptyDirectory();
        }
    }

    @Test
    void allowsWorkspaceEditsAndEnforcesReviewAndCheckoutBoundary()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout")).toRealPath();
        Path outside = Files.writeString(folder.resolve("outside.txt"), "outside\n");
        Files.createSymbolicLink(checkout.resolve("outside-link"), outside);
        Path git = Files.createDirectory(checkout.resolve(".git"));
        Path config = Files.writeString(git.resolve("config"), "protected\n");
        List<Approval> requests = new CopyOnWriteArrayList<>();
        SessionOptions automatic = SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).build();
        SessionOptions reviewed = SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).approveEveryEdit()
                .approvals(approval -> {
                    requests.add(approval);
                    boolean allow = approval instanceof Approval.FileChange change && change.paths().equals(List.of(checkout.resolve("allowed.txt")));
                    return CompletableFuture.completedStage(allow ? Decision.ALLOW_ONCE : Decision.DENY);
                }).build();
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                .apiKey(() -> "fixture-key-first").build()) {
            try (Session session = agent.startSession(automatic)) {
                assertThat(session.run(Input.text("write-auto"))).isInstanceOf(TurnResult.Completed.class);
                SessionRef reference = session.ref().orElseThrow();
                assertThatThrownBy(() -> agent.resumeSession(reference, SessionOptions.in(checkout).build()))
                        .isInstanceOf(AgentException.class).hasMessageContaining("original checkout, access");
            }
            try (Session session = agent.startSession(reviewed)) {
                assertThat(session.run(Input.text("write-reviewed"))).as("Approval requests: %s", requests).isInstanceOf(TurnResult.Completed.class);
            }
        }
        assertThat(Files.readString(checkout.resolve("auto.txt"))).isEqualTo("automatic\n");
        assertThat(Files.readString(checkout.resolve("allowed.txt"))).isEqualTo("edited\n");
        assertThat(checkout.resolve("denied.txt")).doesNotExist();
        assertThat(outside).hasContent("outside\n");
        assertThat(config).hasContent("protected\n");
        assertThat(requests).hasSize(2).allSatisfy(approval -> assertThat(approval).isInstanceOf(Approval.FileChange.class));
        assertThat(requests.stream().map(Approval.FileChange.class::cast).map(Approval.FileChange::paths))
                .containsExactly(List.of(checkout.resolve("allowed.txt")), List.of(checkout.resolve("denied.txt")));
    }

    @Test
    void deliversManagedInstructionsHostToolsAndQuestionsInReadOnlySession()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        Path protectedFile = Files.writeString(checkout.resolve("protected.txt"), "protected\n");
        Path denied = Files.writeString(checkout.resolve("denied-read.txt"), "denied\n");
        Files.createSymbolicLink(checkout.resolve("denied-alias"), denied);
        List<String> calls = new CopyOnWriteArrayList<>();
        List<String> callbackThreads = new CopyOnWriteArrayList<>();
        List<Question> questions = new CopyOnWriteArrayList<>();
        Tool lookup = Tool.of("lookup", "Look up a key", Lookup.class, input -> {
            calls.add(input.key());
            callbackThreads.add(Thread.currentThread().getName());
            return "looked up " + input.key();
        });
        SessionOptions options = SessionOptions.in(checkout).instructions("Use the host lookup tool.").tool(lookup).denyRead(denied)
                .questions(question -> {
                    questions.add(question);
                    return CompletableFuture.completedStage(Map.of(question.items().getFirst().id(), List.of("Fast plan")));
                })
                .approvals(_ -> { throw new AssertionError("Read-only edits must not reach the approval handler"); })
                .build();
        try (ExecutorService callbacks = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("agy-host-callback").factory());
                Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                        .apiKey(() -> "fixture-key-first").callbackExecutor(callbacks).build()) {
            SessionRef reference;
            try (Session session = agent.startSession(options)) {
                assertThat(session.run(Input.text("use-host-tool"))).isInstanceOf(TurnResult.Completed.class);
                reference = session.ref().orElseThrow();
            }
            try (Session session = agent.resumeSession(reference, options)) {
                assertThat(session.run(Input.text("use-host-tool"))).isInstanceOf(TurnResult.Completed.class);
            }
        }
        assertThat(calls).containsExactly("alpha", "alpha");
        assertThat(callbackThreads).containsExactly("agy-host-callback", "agy-host-callback");
        assertThat(questions).hasSize(2).allSatisfy(question -> assertThat(question.items()).singleElement().satisfies(item -> {
            assertThat(item.text()).isEqualTo("Which plan?");
            assertThat(item.options()).extracting(Question.Option::label).containsExactly("Fast plan", "Slow plan");
        }));
        assertThat(protectedFile).hasContent("protected\n");
    }

    @Test
    void runsApprovedCommandsWithinWorkspaceAndExplicitGrants()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        Path allowed = Files.createDirectory(folder.resolve("granted"));
        Path denied = Files.writeString(checkout.resolve("denied-read.txt"), "keep");
        Files.writeString(checkout.resolve("Makefile"), "test:\n\tprintf 'built' > built.txt\n");
        List<Approval.Command> asked = new CopyOnWriteArrayList<>();
        SessionOptions options = SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).writableRoot(allowed)
                .denyRead(denied).denyCommand("touch").approveEveryCommand()
                .approvals(approval -> {
                    Approval.Command command = (Approval.Command) approval;
                    asked.add(command);
                    return CompletableFuture.completedStage(command.command().endsWith("unapproved.txt") ? Decision.DENY : Decision.ALLOW_ONCE);
                }).build();
        try (ExecutorService callbacks = Executors.newSingleThreadExecutor();
                Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                        .apiKey(() -> "fixture-key-first").callbackExecutor(callbacks).build();
                Session session = agent.startSession(options)) {
            assertThat(session.run(Input.text("run-commands"))).isInstanceOf(TurnResult.Completed.class);
        }
        assertThat(checkout.resolve("built.txt")).hasContent("built");
        assertThat(allowed.resolve("copied.txt")).hasContent("built");
        assertThat(checkout.resolve("denied-command.txt")).doesNotExist();
        assertThat(checkout.resolve("unapproved.txt")).doesNotExist();
        assertThat(folder.resolve("outside.txt")).doesNotExist();
        assertThat(asked).hasSize(6).allMatch(Approval.Command::sandboxed);
    }

    @Test
    void controlsCommandNetworkIndependentlyOfProviderNetwork()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                .apiKey(() -> "fixture-key-first").build()) {
            try (Session session = agent.startSession(SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).build())) {
                assertThat(session.run(Input.text("command-network-off"))).isInstanceOf(TurnResult.Completed.class);
            }
            try (Session session = agent.startSession(SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).allowNetwork().build())) {
                assertThat(session.run(Input.text("command-network-on"))).isInstanceOf(TurnResult.Completed.class);
            }
        }
    }

    @Test
    void interruptsAnOwnedWorkspaceCommand()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        Path marker = checkout.resolve("command.pid");
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                .apiKey(() -> "fixture-key-first").build();
                Session session = agent.startSession(SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).build())) {
            Turn turn = session.start(Input.text("hold-command"), _ -> {});
            long deadline = System.nanoTime() + SECONDS.toNanos(10);
            while (!Files.exists(marker) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            long pid = Long.parseLong(Files.readString(marker).strip());
            assertThat(turn.interrupt().toCompletableFuture().get(20, SECONDS)).isInstanceOf(TurnResult.Interrupted.class);
            assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
        }
        assertThat(folder.resolve("state/processes/intents")).isEmptyDirectory();
    }

    @Test
    void allowsLocalCommitsInOnlyTheLinkedWorktree()
            throws Exception
    {
        Path main = Files.createDirectory(folder.resolve("main")).toRealPath();
        Path checkout = main.resolveSibling("checkout");
        git(main, "init", "-b", "main");
        git(main, "config", "user.name", "Antigravity fixture");
        git(main, "config", "user.email", "fixture@example.invalid");
        git(main, "commit", "--allow-empty", "-m", "Initial");
        git(main, "update-ref", "refs/remotes/origin/main", "HEAD");
        String original = git(main, "rev-parse", "HEAD");
        git(main, "worktree", "add", "-b", "task", checkout.toString());
        Files.writeString(checkout.resolve("change.txt"), "committed");
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                .apiKey(() -> "fixture-key-first").build()) {
            assertThatThrownBy(() -> agent.startSession(SessionOptions.in(main).access(Access.WORKSPACE_WRITE).allowCommits().build()))
                    .isInstanceOf(AgentException.class).hasMessageContaining("linked worktree");
            try (Session session = agent.startSession(SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).allowCommits().build())) {
                assertThat(session.run(Input.text("local-commit"))).isInstanceOf(TurnResult.Completed.class);
            }
        }
        assertThat(git(checkout, "log", "-1", "--format=%s")).isEqualTo("Local task");
        assertThat(git(checkout, "show", "HEAD:change.txt")).isEqualTo("committed");
        assertThat(git(main, "rev-parse", "HEAD")).isEqualTo(original);
        assertThat(git(main, "rev-parse", "refs/remotes/origin/main")).isEqualTo(original);
        assertThat(main.resolve("foreign.txt")).doesNotExist();
    }

    @Test
    void supportsToolsOnlyAndTextOnlyWithoutNativeTools()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        Files.writeString(checkout.resolve("protected.txt"), "checkout");
        Tool lookup = Tool.of("lookup", "Look up a key", Lookup.class, input -> "looked up " + input.key());
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state"))
                .apiKey(() -> "fixture-key-first").build()) {
            try (Session session = agent.startSession(SessionOptions.in(checkout).access(Access.TOOLS_ONLY).tool(lookup).build())) {
                assertThat(session.run(Input.text("tools-only"))).isInstanceOf(TurnResult.Completed.class);
            }
            try (Session session = agent.startSession(SessionOptions.in(checkout).access(Access.TOOLS_ONLY).build())) {
                assertThat(session.run(Input.text("text-only"))).isInstanceOf(TurnResult.Completed.class);
            }
        }
        assertThat(checkout.resolve("protected.txt")).hasContent("checkout");
    }

    @Test
    void withdrawsAcpApprovalWhenTheTurnIsInterrupted()
            throws Exception
    {
        Path checkout = Files.createDirectory(folder.resolve("checkout"));
        CompletableFuture<Approval> asked = new CompletableFuture<>();
        CompletableFuture<Decision> pending = new CompletableFuture<>();
        SessionOptions options = SessionOptions.in(checkout).access(Access.WORKSPACE_WRITE).approveEveryEdit()
                .approvals(approval -> {
                    asked.complete(approval);
                    return pending;
                }).build();
        try (Agent agent = Antigravity.builder().executable(fakeCli()).stateDirectory(folder.resolve("state")).apiKey(() -> "fixture-key-first").build();
                Session session = agent.startSession(options)) {
            Turn turn = session.start(Input.text("hold-approval"), _ -> {});
            assertThat(asked.get(20, SECONDS)).isInstanceOf(Approval.FileChange.class);
            assertThat(turn.interrupt().toCompletableFuture().get(20, SECONDS)).isInstanceOf(TurnResult.Interrupted.class);
            assertThat(pending).isCancelled();
        }
    }

    public record Lookup(String key) {}

    private static String git(Path checkout, String... arguments)
            throws Exception
    {
        var command = new ArrayList<>(List.of("/usr/bin/git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(checkout.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        assertThat(process.waitFor(10, SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8).strip();
        assertThat(process.exitValue()).as("%s: %s", arguments, output).isZero();
        return output;
    }

    private Path fakeCli()
            throws IOException
    {
        Path executable = folder.resolve("agy");
        try (var fixture = requireNonNull(getClass().getResourceAsStream("fake-agy.sh"))) {
            Files.copy(fixture, executable);
        }
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }
}

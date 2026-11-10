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
package io.github.shiftinglabs.codej.example;

import io.github.shiftinglabs.codej.Access;
import io.github.shiftinglabs.codej.Agent;
import io.github.shiftinglabs.codej.AgentEvent;
import io.github.shiftinglabs.codej.AgentStatus;
import io.github.shiftinglabs.codej.Approval;
import io.github.shiftinglabs.codej.ApprovalHandler;
import io.github.shiftinglabs.codej.Claude;
import io.github.shiftinglabs.codej.Codex;
import io.github.shiftinglabs.codej.Decision;
import io.github.shiftinglabs.codej.Input;
import io.github.shiftinglabs.codej.Limits;
import io.github.shiftinglabs.codej.Model;
import io.github.shiftinglabs.codej.Question;
import io.github.shiftinglabs.codej.Session;
import io.github.shiftinglabs.codej.SessionOptions;
import io.github.shiftinglabs.codej.SessionRef;
import io.github.shiftinglabs.codej.Tool;
import io.github.shiftinglabs.codej.Turn;
import io.github.shiftinglabs.codej.TurnOptions;
import io.github.shiftinglabs.codej.TurnResult;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedStage;

/** Every code block in the README, so the README cannot drift from the API. */
final class ReadmeExamples
{
    private ReadmeExamples() {}

    interface Tracker
    {
        String text(String key);
    }

    interface Ui
    {
        CompletionStage<Map<String, List<String>>> ask(Question question);
    }

    interface Chat
    {
        void post(String text);
    }

    interface Button
    {
        void onClick(Runnable action);
    }

    interface Vault
    {
        String gatewayToken();

        String deepSeekKey();
    }

    static CompletionStage<Decision> askOnSlack(Approval approval)
    {
        return completedStage(Decision.DENY);
    }

    // Quick start
    public static void main(String[] args)
            throws InterruptedException
    {
        try (Agent codex = Codex.builder().build();
                Session session = codex.startSession(SessionOptions.in(Path.of(".")).build())) {
            TurnResult result = session.run(Input.text("Summarize this repository in three lines"));
            if (result instanceof TurnResult.Completed done) {
                System.out.println(done.finalText());
            }
        }
    }

    // Check the agent first
    static void check(Agent codex)
    {
        AgentStatus status = codex.status();
        if (!status.ready()) {
            System.err.println(status.detail());
            status.signInCommand().ifPresent(command -> System.err.println("Sign in with: " + command));
            return;
        }
        for (Model model : codex.models()) {
            System.out.println(model.id() + (model.isDefault() ? " (default)" : ""));
        }
        for (Limits.Window window : codex.limits().windows()) {
            System.out.println(window.label() + ": " + window.usedPercent() + "% used");
        }
    }

    // Watch a turn as it runs
    static void stream(Session session)
            throws InterruptedException
    {
        Turn turn = session.start(Input.text("Run the tests and fix what fails"), event -> {
            switch (event) {
                case AgentEvent.MessageDelta delta -> System.out.print(delta.delta());
                case AgentEvent.Command command when command.activity() == AgentEvent.Activity.STARTED -> System.out.println("$ " + command.command());
                case AgentEvent.FileChange change -> System.out.println("edited " + change.path());
                default -> {}
            }
        });

        switch (turn.await()) {
            case TurnResult.Completed done -> System.out.println("Done, " + done.usage().outputTokens() + " tokens written");
            case TurnResult.Failed failed -> System.err.println("Failed: " + failed.message());
            case TurnResult.Interrupted _ -> System.err.println("Stopped");
        }
    }

    // Let it write, with approvals
    static Session write(Agent codex, Path checkout)
    {
        Set<String> safe = Set.of("mvn -q test", "mvn -q verify");

        ApprovalHandler approvals = approval -> switch (approval) {
            case Approval.Command command when safe.contains(command.command()) -> completedStage(Decision.ALLOW_ONCE);
            case Approval.FileChange _ -> completedStage(Decision.ALLOW_ONCE);
            default -> askOnSlack(approval);   // completes when a person decides
        };

        return codex.startSession(SessionOptions.in(checkout)
                .access(Access.WORKSPACE_WRITE)
                .approvals(approvals)
                .approvalTimeout(Duration.ofMinutes(10))
                .build());
    }

    // Widen the sandbox
    static Session widen(Agent codex, Path checkout)
    {
        return codex.startSession(SessionOptions.in(checkout)
                .access(Access.WORKSPACE_WRITE)
                .allowNetwork()                                  // builds may download dependencies
                .writableRoot(Path.of(System.getProperty("user.home"), ".m2", "repository"))
                .denyCommand("git push")                         // refused without asking you
                .readOnlySubagents()
                .build());
    }

    // Give it your own tools
    record IssueKey(String key) {}

    static Session tools(Agent codex, Path checkout, Tracker tracker)
    {
        Tool readIssue = Tool.of("read_issue", "Reads one issue by its key, such as ENG-1", IssueKey.class,
                args -> tracker.text(args.key()));

        return codex.startSession(SessionOptions.in(checkout)
                .tool(readIssue)
                .instructions("Read the issue before you change any code.")
                .build());
    }

    static Agent toolServerName()
    {
        Agent codex = Codex.builder().toolServerName("tracker").build();
        return codex;
    }

    // Let it ask questions
    static Session questions(Agent codex, Path checkout, Ui ui)
    {
        return codex.startSession(SessionOptions.in(checkout)
                .questions(ui::ask)   // completes when the user answers
                .build());
    }

    // A text-only helper
    static TurnResult textOnly(Agent codex, String request)
            throws IOException, InterruptedException
    {
        Path empty = Files.createTempDirectory("naming");
        try (Session session = codex.startSession(SessionOptions.in(empty).access(Access.TOOLS_ONLY).build())) {
            return session.run(Input.text("Name this task in at most five words: " + request));
        }
    }

    // Per-turn settings
    static Turn perTurn(Session session)
    {
        TurnOptions quick = TurnOptions.defaults()
                .withModel("gpt-6-astra")
                .withEffort("low")
                .withTimeLimit(Duration.ofMinutes(10));
        return session.start(Input.text("List the modules this change touches"), quick, _ -> {});
    }

    // Steer, stop and resume
    static Session steerStopResume(Agent codex, Session session, Path checkout, Button stop)
    {
        Turn turn = session.start(Input.text("Upgrade the build to Java 25"), _ -> {});
        if (codex.status().capabilities().steering()) {
            turn.steer(Input.text("Leave the Gradle wrapper alone"));
        }
        stop.onClick(turn::interrupt);

        String saved = session.ref().orElseThrow().asString();   // keep it anywhere; empty until the provider saved it
        session.close();

        return codex.resumeSession(SessionRef.parse(saved), SessionOptions.in(checkout).build());
    }

    // Chain turns without blocking
    static CompletionStage<Void> chain(Session session, Chat chat)
    {
        return session.start(Input.text("Write release notes for the last ten commits"), _ -> {})
                .result()
                .thenApply(result -> result instanceof TurnResult.Completed done ? done.finalText() : "No notes: " + result)
                .thenAccept(chat::post);
    }

    // Accounts, gateways and Claude
    static List<Agent> accounts(Path workHome, Vault vault)
    {
        Agent work = Codex.builder().home(workHome).account("me@example.com").keepSessionsFor(Duration.ofDays(30)).build();
        Agent proxy = Codex.builder().gateway(URI.create("https://llm.example.com/v1"), vault::gatewayToken).build();
        Agent deepSeek = Codex.builder().deepSeek(vault::deepSeekKey).build();
        Agent claude = Claude.builder()
                .runOutsideSandbox("mvn", "./mvnw")
                .allowUnixSocket(Path.of("/var/run/docker.sock"))
                .autoModeRules("Never push to a protected branch")
                .build();
        return List.of(work, proxy, deepSeek, claude);
    }

    // After a crash
    static Agent afterCrash(Path state)
    {
        Agent codex = Codex.builder().stateDirectory(state).build();   // stops what an earlier run left
        for (Path directory : codex.unconfirmedDirectories()) {
            System.err.println("A process may still be writing in " + directory);
        }
        return codex;
    }
}

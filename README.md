# shifting-codej

Run [Codex](https://github.com/openai/codex) and [Claude Code](https://github.com/anthropics/claude-code)
from Java through one small API: sessions, turns, live events, approvals, questions, and tools of
your own.

OpenAI ships Codex SDKs for Python and TypeScript; this is one for Java, and it drives Claude Code
the same way. It runs each CLI on your machine and owns every process it starts.

> Draft. Not released yet.

## Requirements

- Java 25 or newer, the current LTS release.
- macOS 14 or newer on Apple silicon. Intel Macs and Linux are planned.
- Codex: nothing to install. The first time you build a Codex agent, the SDK downloads Codex
  0.164.0, the release it's tested against, from OpenAI's npm package: about 120 MB, exactly as
  OpenAI built and signed it. To run your own Codex instead, pass `Codex.builder().executable(path)`.
- Internet access the first time, unless you pass `executable(path)`.
- Claude Code 2.1.200 or newer, installed as `claude`.
- Run with `--enable-native-access=ALL-UNNAMED`, or the JDK warns: the SDK starts each CLI in its own
  process session through the JDK's foreign-function API, so it can stop the CLI's whole process tree.

## Install

```xml
<dependency>
    <groupId>io.github.shifting-labs</groupId>
    <artifactId>shifting-codej</artifactId>
    <version>0.164.0-1</version>
</dependency>
```

- The SDK has no dependencies of its own on your classpath.
- The SDK's version starts with the Codex release it runs: `0.164.0-1` runs Codex 0.164.0, and
  `0.164.0-2` would be a fix on the same Codex. `Codex.VERSION` names it.
- The first `Codex.builder().build()` downloads Codex into `~/Library/Caches/shifting-codej/codex/`
  and checks it against hashes recorded in the SDK; anything else is refused. Later builds reuse that
  copy without a network request. Versions no build has used for 30 days are deleted.
- When the download fails, `build()` still returns an agent; `status()` says why and suggests
  `executable(path)`.

### Behind a company npm mirror

If your network allows only an internal npm mirror, point the SDK at it with an environment variable
or a Java system property; the property wins if both are set:

```bash
export CODEJ_CODEX_REGISTRY=https://artifactory.example.com/artifactory/api/npm/npm-remote/
java -Dcodej.codex.registry=https://artifactory.example.com/artifactory/api/npm/npm-remote/ -jar my-app.jar
```

The SDK then downloads `<mirror>/@openai/codex/-/codex-0.164.0-darwin-arm64.tgz` from the mirror only,
with the same checks. To see whether your mirror has it:

```bash
curl -I https://artifactory.example.com/artifactory/api/npm/npm-remote/@openai/codex/-/codex-0.164.0-darwin-arm64.tgz
```

A mirror that needs a login isn't supported; install Codex with your own tools and pass
`executable(path)`.

## Quick start

```java
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
```

A new session can only read, and every request beyond that is denied. Turn on what you need.

## Five types

| Type | What it is |
| --- | --- |
| `Agent` | One provider account. Build it with `Codex.builder()` or `Claude.builder()`. |
| `Session` | One conversation in one working directory. |
| `Turn` | One message you sent, and the agent's work on it. |
| `AgentEvent` | What the agent does during a turn, as it happens. |
| `TurnResult` | How the turn ended: `Completed`, `Failed` or `Interrupted`. |

Everything else configures these: `SessionOptions`, `TurnOptions`, `Input`, `Tool`,
`ApprovalHandler` and `QuestionHandler`.

## Check the agent first

```java
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
```

- `status()` never throws for a missing or signed-out CLI. It says what is wrong: `CLI_MISSING`,
  `UNSUPPORTED_VERSION`, `SIGN_IN_REQUIRED`, `TEMPORARILY_UNAVAILABLE` (try again later) or
  `UNAVAILABLE`.
- `limits()` reads the account's usage limits without spending a request.
- The first session checks the CLI too, so you can skip `status()` and catch `AgentException`.

## Watch a turn as it runs

```java
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
```

- A turn's events arrive in order, one at a time, on the agent's callback executor: a new virtual
  thread per callback by default, never the thread that reads the CLI. A slow listener cannot stall
  the agent.
- `result()` completes after the listener has returned for the turn's last event.
- Keep the `default` case. Providers keep adding kinds of work, so new event types can arrive in a
  minor release. `TurnResult` and `Approval` change only in a major release, so their `switch` can
  list every case.

## Let it write, with approvals

```java
Set<String> safe = Set.of("mvn -q test", "mvn -q verify");

ApprovalHandler approvals = approval -> switch (approval) {
    case Approval.Command command when safe.contains(command.command()) -> completedStage(Decision.ALLOW_ONCE);
    case Approval.FileChange _ -> completedStage(Decision.ALLOW_ONCE);
    default -> askOnSlack(approval);   // completes when a person decides
};

Session session = codex.startSession(SessionOptions.in(checkout)
        .access(Access.WORKSPACE_WRITE)
        .approvals(approvals)
        .approvalTimeout(Duration.ofMinutes(10))
        .build());
```

`Access` is `TOOLS_ONLY`, `READ_ONLY` or `WORKSPACE_WRITE`, and the provider's sandbox enforces it.
Anything beyond it goes to your handler, which may answer at once or later. A decision that does
not arrive in time, a failed stage, a thrown exception, or a decision outside `approval.choices()`
all count as `DENY`. `Decision.deny(reason)` tells the agent why. `ALLOW_FOR_SESSION` also allows
matching later requests, when the provider offers it. A command's request says whether it would run
inside the sandbox, and the hosts it would reach when the provider names them.

## Widen the sandbox

```java
Session session = codex.startSession(SessionOptions.in(checkout)
        .access(Access.WORKSPACE_WRITE)
        .allowNetwork()                                  // builds may download dependencies
        .writableRoot(Path.of(System.getProperty("user.home"), ".m2", "repository"))
        .denyCommand("git push")                         // refused without asking you
        .readOnlySubagents()
        .build());
```

Each setting is off until you turn it on:

| Setting | What it allows or refuses |
| --- | --- |
| `allowNetwork()` | Commands may use the network. |
| `allowWebSearch()` | The agent may search and fetch the web with its own tools. |
| `allowCommits()` | Commands may commit, also in a linked Git worktree. |
| `writableRoot(path)` | One more folder the agent may write. |
| `denyCommand(prefix)` | Commands that start with it are refused. |
| `denyRead(path)` | The agent may not read it. Claude only. |
| `noSubagents()`, `readOnlySubagents()` | No helpers, or helpers that only read. |
| `approveEveryCommand()`, `approveEveryEdit()` | Every command, or every edit, goes to your handler first. |
| `fixedSandbox()` | Nothing during the session can widen the sandbox. |

## Give it your own tools

```java
record IssueKey(String key) {}

Tool readIssue = Tool.of("read_issue", "Reads one issue by its key, such as ENG-1", IssueKey.class,
        args -> tracker.text(args.key()));

Session session = codex.startSession(SessionOptions.in(checkout)
        .tool(readIssue)
        .instructions("Read the issue before you change any code.")
        .build());
```

The record's components become the tool's JSON schema, and each call binds into a new record. A
handler may block. An exception it throws reaches the agent as a failed call, and the turn goes on.
Only the root agent can call a tool unless you pass `readIssue.forSubagents()`. For arguments a
record cannot describe, use `Tool.withSchema(name, description, jsonSchema, handler)`.

The model reaches your tools through an MCP server named `codej`: Claude sees `mcp__codej__read_issue`,
and both providers get a `codej_call_tool` catalog. To use your application's name instead:

```java
Agent codex = Codex.builder().toolServerName("tracker").build();
```

## Let it ask questions

```java
Session session = codex.startSession(SessionOptions.in(checkout)
        .questions(ui::ask)   // completes when the user answers
        .build());
```

Without a question handler, the agent is told it cannot ask, and decides for itself.

## A text-only helper

```java
Path empty = Files.createTempDirectory("naming");
try (Session session = codex.startSession(SessionOptions.in(empty).access(Access.TOOLS_ONLY).build())) {
    return session.run(Input.text("Name this task in at most five words: " + request));
}
```

`TOOLS_ONLY` removes the shell, file and web tools; with no tools of your own, the agent can only
answer.

## Per-turn settings

```java
TurnOptions quick = TurnOptions.defaults()
        .withModel("gpt-6-astra")
        .withEffort("low")
        .withTimeLimit(Duration.ofMinutes(10));
session.start(Input.text("List the modules this change touches"), quick, _ -> {});
```

- `withServiceTier(tier)` picks one of the model's `serviceTiers()`.
- A turn past its time limit ends as `Failed` with `TIME_LIMIT`.
- A turn the provider never received ends as `Failed` with `NOT_SENT`. One it may or may not have
  received ends with `DELIVERY_UNCERTAIN`, so look before you send it again.

## Steer, stop and resume

```java
Turn turn = session.start(Input.text("Upgrade the build to Java 25"), _ -> {});
if (codex.status().capabilities().steering()) {
    turn.steer(Input.text("Leave the Gradle wrapper alone"));
}
stop.onClick(turn::interrupt);

String saved = session.ref().orElseThrow().asString();   // keep it anywhere; empty until the provider saved it
session.close();

Session resumed = codex.resumeSession(SessionRef.parse(saved), SessionOptions.in(checkout).build());
```

- `interrupt()` stops the provider's work, then `result()` holds `Interrupted`.
- `steer` throws `DeliveryUncertainException` when the provider may or may not have the input.
- Between turns a session may keep its process for the next one; `keepsProcess()` says so.
- `close()` returns only once the session's process has exited, and the conversation stays
  resumable.

## Chain turns without blocking

```java
session.start(Input.text("Write release notes for the last ten commits"), _ -> {})
        .result()
        .thenApply(result -> result instanceof TurnResult.Completed done ? done.finalText() : "No notes: " + result)
        .thenAccept(chat::post);
```

`result()` never completes exceptionally, so a chain needs no error handling: a failed turn is a
`TurnResult.Failed`. The stage is read-only, so no caller can complete it. If you use Guava, wrap it
in a `ListenableFuture`:

```java
static <T> ListenableFuture<T> listenable(CompletionStage<T> stage)
{
    SettableFuture<T> future = SettableFuture.create();
    stage.whenComplete((value, failure) -> {
        if (failure == null) {
            future.set(value);
        }
        else {
            future.setException(failure);
        }
    });
    return future;
}
```

## Accounts, gateways and Claude

```java
Agent work = Codex.builder().home(workHome).account("me@example.com").keepSessionsFor(Duration.ofDays(30)).build();
Agent proxy = Codex.builder().gateway(URI.create("https://llm.example.com/v1"), vault::gatewayToken).build();
Agent deepSeek = Codex.builder().deepSeek(vault::deepSeekKey).build();
Agent openAi = Codex.builder().apiKey(vault::openAiKey).build();
Agent claude = Claude.builder()
        .runOutsideSandbox("mvn", "./mvnw")
        .allowUnixSocket(Path.of("/var/run/docker.sock"))
        .autoModeRules("Never push to a protected branch")
        .build();
```

- Every session API works the same on each agent.
- Each agent keeps its login in its own folder, never `~/.codex` or `~/.claude`, so your own CLI
  settings never reach a session.
- Secrets are a `Supplier<String>`, read when a process starts, passed only in its environment, and
  never written to disk.
- `deepSeek(key)` runs DeepSeek models through Codex, with no ChatGPT login.
- `apiKey(key)` signs Codex in with an OpenAI API key instead. Each Codex process signs in with it
  right after it starts and keeps it only in memory, so Codex's environment, its home and the
  commands the agent runs never see it.
- Both CLIs keep every conversation's transcript forever. `keepSessionsFor(age)` has the agent delete
  the ones unused for longer, while it runs; a deleted conversation can't be resumed.
- On Claude, `runOutsideSandbox` and `allowUnixSocket` apply only to sessions that may use the
  network. `autoModeRules` turns on Claude's own reviewer, which checks the agent's actions before
  they run; `reviewSandboxedCommands()` has it check sandboxed commands too.

## After a crash

```java
Agent codex = Codex.builder().stateDirectory(state).build();   // stops what an earlier run left
for (Path directory : codex.unconfirmedDirectories()) {
    System.err.println("A process may still be writing in " + directory);
}
```

Each agent keeps its working files and the records of the processes it starts in a state folder.
Building the first agent on that folder stops whatever an earlier run of your program left there.
`unconfirmedDirectories()` names the working directories where a process could not be shown stopped,
so keep other work out of them. Each call checks again.

## What the SDK promises

- **No nulls.** An absent value is an `Optional`.
- **Immutable values.** Every record, and every collection it holds, is fixed once built.
- **Your code runs on your executor**, set with `callbackExecutor(...)`, never on the SDK's I/O
  threads.
- **A turn's future never fails.** `AgentException` is thrown only when work cannot start at all.
- **Safe defaults.** Read-only access, every approval denied, and a five-minute timeout on requests.
- **Processes are owned.** `close()` waits for the CLI processes it started to exit, and a new run
  stops what a crashed one left.
- **One tested Codex.** Each SDK release pins the Codex release it was tested with, and runs only
  the files whose checksums it records.

## License

Apache License 2.0.

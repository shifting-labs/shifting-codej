# Bounded command-tree execution

Status: pure-Java host-backend integration implemented and verified; native enforcement remains a separate follow-up.
Extends [tool resources](tool-spi.md) and [shared tool budgets](tool-scheduling.md).

## Compatibility and scope

Keep `shell_command`, `CodingTools`, existing command request/result records, process supervisor,
session references and checkpoint formats unchanged. Add `run_command` as an explicitly installed
typed handler. Existing `CommandExecutor` implementations remain source/binary compatible through
default methods: `supportsCommandTrees()` returns false and `startTree(...)` rejects before effects.
There is no automatic downgrade to the single-process implementation.

`CommandLimits` is host configuration, never model input. It bounds PIDs/threads, CPU, memory,
scratch, lifetime and retained bytes per output stream. Defaults are 64 PIDs/threads, one CPU,
512 MiB memory, 64 MiB scratch, 30 seconds and 8 KiB per stream. Aggregate active invocations use
the existing shared execution budget; runtime capacity remains owned until stopped and reconciled.
The backend must reject unsupported limits rather than treating them as advisory.

## Execution and authority

`CommandExecution` extends the existing `ToolExecution<CommandResult>` contract. Its nonblocking,
thread-safe `output()` returns a bounded immutable `CommandOutput`; reading output never starts work.
Completion and verified shutdown are separate facts. Cancellation requests termination; only a
verified stop permits resource release. `committed()` acknowledges ownership only after the engine
durably stores the outcome. A handle controls only its own invocation, including after a later start.

`RunCommandTool` requests workspace-read and process-execution authority. Registration uses the
existing tool-provider path, policy, mandatory approval, journal and shared budget. A model chooses
argv, a relative directory and a bounded timeout; it cannot raise host limits, provide environment
credentials, enable network or expand filesystem access. Model-facing output uses existing engine
redaction and result-size limits. No background command is detached from tool execution ownership.

The existing process supervisor explicitly provides lifecycle observation, not containment of an
unobserved descendant that changes sessions. Unlike the old fork-denied command, root exit is not
proof that a command tree stopped. Do not call `stopAfterWorkFinished()` merely because the root
exited. Unknown shutdown retains ownership and blocks unsafe execution/replay.

## Pure-Java integration and enforcement boundary

The user selected **no Docker**, with pure Java preferred and no new dependencies for this stage.
`CommandToolProvider` installs a trusted host-supplied backend through the existing `ToolProvider`
SPI. There is no runtime download, container, image, new Maven dependency, automatic subprocess
fallback or worker pool. The existing fork-denied Seatbelt shell remains unchanged.

`Configuration` captures validated host limits and a thread-safe factory. `definitions()` describes
`run_command` without opening the backend. `open()` requires the engine's configured shared tool
budget before invoking the factory. The additive default `ToolSetContext.toolExecutionBudget()` is
empty for old assembly contexts; the Java engine supplies its existing borrowed budget. The
provider inspects presence only: the dispatcher acquires once, avoiding nested-acquisition deadlocks.
Share the same budget instance across engines to bound their aggregate active command invocations.

The factory validates that the application backend can enforce **all** limits, read-only workspace,
protected-path exclusions, sanitized environment, no network and durable cleanup/recovery. It returns
one fresh session-owned `Backend`, never a shared executor reused across sessions. Failure before
return belongs to the factory's cleanup contract. Once returned, the engine owns its `ToolSet`
before inspecting capabilities. Unsupported capability or failed close therefore keeps cleanup
retryable. Successful close is idempotent, and closed sets no longer expose tools.

Pure Java manages the registration, typed contracts and ownership lifecycle. JDK `ProcessBuilder`
starts processes and configures environment/streams; `ProcessHandle` exposes asynchronously changing
process snapshots with documented identity races. They are not a portable hard-quota or process-tree
containment mechanism. This implementation does not claim that polling descendants enforces CPU,
memory or PID limits, or that root exit proves an escaped child stopped. See the official
[ProcessBuilder](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessBuilder.html)
and [ProcessHandle](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessHandle.html)
contracts. The default backend reports tree support as false. A real application backend must use
an available platform enforcement capability and reject unsupported requests before effects.

```java
ToolExecutionBudget shared = ToolExecutionBudget.bounded(1);
var commands = new CommandToolProvider.Configuration(CommandLimits.defaults(),
        (limits, context) -> application.openContainedCommands(limits, context));

// Both facades expose the existing toolProvider and toolExecutionBudget setters:
// ClaudeApi.builder().toolExecutionBudget(shared)
//         .toolProvider(new CommandToolProvider(), commands) ...
// DeepSeekApi.builder() uses the identical provider/configuration.
// Session access must grant workspace reads and process execution, with per-call approval.
```

The application factory opens resources without running model-requested commands. The backend
records opaque ownership through `recordPrepared` before effects and reconciles its own durable
resource identities after a crash. Unknown effects keep the engine's started receipt unresolved;
stopping work does not authorize replay or invent a completed result. No new checkpoint format or
provider-specific engine branch is introduced.

## Checkbox plan

- [x] Inspect existing containment, durable launch, tool-provider and budget boundaries.
- [x] Define additive tree-execution, limits and output contracts without changing existing shapes.
- [x] Add a separately registered typed handler that checks authority and host timeout before effects.
- [x] Select host-supplied enforcement and implement session-owned registration without new dependencies.
- [x] Require existing shared admission; retain backend ownership until stop verification and durable acknowledgement.
- [x] Test prepared ownership, unresolved recovery, cancellation and acknowledgement ordering through scripted host backends.
- [x] Add reusable command-tree contracts, fake-backend and upstream facade tests.
- [x] Run focused constrained tests and packaged/API compatibility checks; record evidence.
- [x] Update engine plans, preserve unrelated changes and commit the host-backend integration stage.


## Contract-stage validation

The isolated snapshot based on `467fd97` passed 23 focused cases: `TestCommandTreeContracts`
(16) and the existing fake `TestCommandExecutor` contracts (7), zero failures/errors/skips.
Checkstyle, license checks and CLI packaging passed. Maven ran offline, serially, at low priority,
with one active CPU, one test fork, a 512 MiB heap and SerialGC; JUnit parallel execution was disabled.
The four pre-existing Claude CLI edits were excluded and verified byte-for-byte unchanged.
No agents, command trees, containers, live providers or broad test suites ran. These checks validate
additive contracts and handler dispatch only; this initial contract run preceded the host-supplied backend decision. Integration, packaged SDK/API
compatibility and final-stage evidence follow below.

## Follow-up native enforcement

- [ ] Implement a platform backend only when it can enforce the declared bounds and prove shutdown;
      keep it optional and prefer JDK facilities where they supply the required guarantee.
- [ ] Run that backend's real OS containment and crash-recovery tests separately from scripted contract tests.

The current delivery is the Java integration framework and host-backend contract. It does not bundle
a new native command-tree runtime or claim real OS enforcement from fake-backend tests.


## Host-backend integration validation

The final isolated snapshot based on `467fd97` passed **123 focused Java cases**, zero failures,
errors or skips:

- **89 CLI cases**: tool providers (24), dispatcher (17), parallel dispatcher (9), shared execution
  budget (7), existing command executor (7), new command contracts/values (16), and the new command
  provider including five inherited reusable execution contracts (9).
- **34 SDK cases**: each existing public facade (13 each), and both providers' new fake-HTTP command
  integration (8). These exercise approval and denial, mandatory shared admission, prepared resources
  in the actual engine-profile checkpoint, redacted native tool continuation, completed-session resume
  without replaying a command, unsupported backend cleanup, and unverified shutdown retaining capacity.
- **Five compatibility-checker controls** passed. The final shaded artifact passed the published
  `0.164.0-1` API/shape/shading/checksum checks and all packaged consumers. `CommandToolsConsumer`
  uses only the JDK and shaded jar to invoke a scripted host command through each public API facade,
  with shared admission, approval, typed output and stop-before-acknowledgement.
- A separate comparison with the preceding engine artifact verified **138 existing types**. All old
  descriptors, record components, enum order and sealed shapes remain unchanged. The only additions
  to existing types are the two command-executor defaults and the assembly budget default; no new
  mandatory implementation method, existing record component or persistence version was introduced.

Candidate shaded artifact SHA-256:
`5bac3b5d303079d247811129127cd466c699f55e9401ed1e2dc8eaa07422ec89`.

Checkstyle, license, strict Javadocs and shading passed. Passing unaffected results were reused while
fixing test formatting and correcting the journal assertion from SDK state storage to engine profile
storage. All checks ran with offline serial Maven, one active CPU, one test fork, disabled JUnit
parallel execution, low priority, a 512 MiB heap and SerialGC. All Maven configuration/dependency
files are byte-identical to the preceding commit. The four unrelated Claude CLI edits were excluded
from the snapshot and preserved byte-for-byte.

No agents, Docker/container runtime, new native command-tree backend, live provider inference,
provider CLIs, broad/Bytequay suites, native builds or simulators ran. The existing packaged
single-process coding-tool checks remain part of compatibility verification. Scripted backend
success does not establish real OS containment, hard resource enforcement or crash recovery for
an application backend; those remain explicit backend responsibilities and require native tests.

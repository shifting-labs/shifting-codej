# Bounded command-tree execution

Status: host-backend integration and opt-in built-in Linux backend implemented. Portable lifecycle tests pass; real Linux enforcement remains an explicit acceptance gate.
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

- [x] Implement an opt-in Linux backend using existing OS enforcement, with actual kernel checks before releasing a command.
- [ ] Add another platform backend only when it can enforce every bound and prove shutdown.
- [ ] Run that backend's real OS containment and crash-recovery tests separately from scripted contract tests.

The contract milestone above delivered the Java integration framework and host-backend contract.
The built-in implementation below adds an existing-OS backend; fake-service tests do not establish real OS enforcement.


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


## Built-in Linux backend (no Docker)

`BuiltinCommands.configuration(limits)` supplies the existing provider factory. An overload accepts
`CodingTools` solely to bound workspace export: maximum individual file bytes, aggregate search
bytes, visited entries and depth. Model requests/results, `CommandLimits`, existing facades,
`ToolProvider`, process SPI, checkpoints and `shell_command` retain their previous shapes/behavior.
Installation stays explicit and uses the same borrowed engine admission service:

```java
var commands = BuiltinCommands.configuration(CommandLimits.defaults());
var builder = ClaudeApi.builder()
        .toolExecutionBudget(ToolExecutionBudget.bounded(1))
        .toolProvider(new CommandToolProvider(), commands);
// DeepSeekApi uses exactly the same configuration and provider.
```

There is no Docker, downloaded runtime, JNI/native library, new Maven dependency, shell wrapper,
external Java service or extra worker pool. Java handles typed framing, filesystem export,
controller lifecycle, output and recovery. **OS prerequisites remain explicit**: Linux, existing
`/usr/bin/systemd-run` and `systemctl`, a functioning non-root systemd user manager, systemd 257+
private-PID support, unprivileged user namespaces and delegated cgroup v2 CPU/memory/PID controllers.
The current JRE and standard Linux binaries/libraries are mounted read-only. A nonstandard JRE path
containing service-property metacharacters is rejected. macOS, Windows, non-systemd Linux, missing
permissions/features/controllers and unverifiable observations fail closed before model argv is
released. There is no automatic installation or silent fallback. Small PID/memory settings may be
insufficient for the trusted launcher JVM, whose overhead is included in the hard limits.

### Isolation and launch gate

- Export the dedicated workspace through descriptor-relative no-follow reads to a private,
  bounded snapshot. Omit protected metadata, configured profile/runtime directories, this
  invocation's artifacts, symlinks, multiply linked files and special files. A snapshot can
  coexist with the existing coding-resource lease. File edits still use the existing patch tool.
  The snapshot is bounded and race-checked per file; it is not an atomic multi-file filesystem snapshot.
- Start one transient **user service** with read-only root/workspace/runtime, private user/PID,
  mount, IPC, device and network namespaces, empty capabilities, `NoNewPrivileges`, seccomp,
  cgroup quotas, no swap, runtime deadline and control-group termination. Only private `/tmp`
  is writable; tmpfs size bounds scratch and memory quotas include tmpfs pages. Host home,
  credentials, profile/runtime state, user bus and host workspace paths are absent from the root.
- The JRE-only launcher emits a nonce-bound readiness header and waits for input. While gated,
  inspect both the identified unit and the actual process: root inode, distinct namespaces,
  privilege flags/capabilities, read-only mounts, tmpfs quota and actual `cpu.max`, `memory.max`,
  `memory.swap.max` and `pids.max`. Unit configuration alone cannot substitute for kernel proof;
  systemd can ignore unsupported features. Unsupported or incomplete proof closes the gate.
- Deliver a bounded typed binary request over stdin only after validation. Persist release
  intent, then send a separate commit byte and EOF. Missing/truncated/malformed requests, wrong
  nonce, invalid UTF-8, trailing bytes or missing commit cannot start a payload. Model argv never
  appears in systemd arguments or recovery files, so `$`/`%` and quoting remain literal. The
  helper clears the environment again before `ProcessBuilder` starts the command.

A single virtual worker belongs to each admitted invocation. Output is independently byte-bounded
per stream and the readiness header is removed. No future cancellation can cancel the private
owner completion. Cancellation/deadline closes the input gate and stops the service. A terminal
result still requires inactive/absent owned unit, zero main PID, empty saved cgroup and verified
controller shutdown before capacity can be released. Root exit/descendant polling is never proof.
`stopAfterWorkFinished` is used only after authoritative unit/cgroup shutdown, or for a completed,
trusted metadata controller. An unacknowledged creation with no observable unit remains uncertain;
a missing unit alone cannot disprove a late queued creation. Unknown work retains ownership and
cleanup can be retried; an uncertain outcome cannot be acknowledged as completed.

### Private ownership and recovery

Every service/controller intent is fsynced before start. Private version-1 typed records live in
`profile/native-command-state/<owner-uuid>/`; a file lock fences each live backend. Records contain
only opaque unit/launch identities, boot identity, artifact file identity and lifecycle facts,
with bounded scans/decoding and atomic replacements. They contain no argv, environment values,
model messages or tool output. Engine checkpoint schemas remain unchanged.

Opening a backend skips locked owners and reconciles abandoned intents. On the same boot it
recovers metadata controllers, stops the owned unit and checks the cgroup before cleaning artifacts.
A different boot cannot signal recycled PIDs or unit identities. Cleanup checks the private temp
root's parent and file identity and never follows links. Stopped crash receipts and uncommitted
main launch markers remain private evidence: recovery never invents a result or permits tool replay.
Normal `committed()` removes artifacts/receipt and acknowledges the launch only after the engine
has durably stored the outcome. Successful close is idempotent; an unverified close is retryable.
A large unreconciled crash archive fails with a bounded-reconciliation error rather than scanning
without limit. Administrative reconciliation/retention of those receipts is a later operational feature.

### Built-in checklist

- [x] Add the opt-in factory through the existing provider and shared budget, without dependencies.
- [x] Add bounded workspace export, typed JRE-only launch framing and kernel-validation gate.
- [x] Add OS cgroup/namespace configuration, bounded output, cancellation and verified shutdown.
- [x] Persist ownership before effects and retain uncertain crash outcomes without replay.
- [x] Cover framing, quotas, mounts, protected paths, lifecycle, retries and abandoned ownership with fakes.
- [x] Add explicit Linux acceptance tests for real child processes, read-only files, scratch, output and shutdown.
- [ ] Run Linux acceptance on a prepared systemd host, including network, namespace-escape, quota exhaustion and crash injection.
- [ ] Add portable hard-containment implementations for other platforms only with equivalent enforcement.

Linux acceptance is intentionally opt-in:

```sh
# Prepared non-root Linux host; run only this suite, serially.
mvn -o -T1 -pl shifting-codej-cli -am -Dtest=TestLinuxNativeCommands \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcodej.test.nativeCommands=true \
  -Dcodej.test.forks=1 -Djunit.jupiter.execution.parallel.enabled=false test
```

This acceptance target fails if the advertised isolation is unavailable; it never accepts fake or
weaker execution. The two real-Linux cases are skipped on this macOS development host. Broader
Linux security/crash acceptance is still required before calling this backend production-validated.

Primary OS references: [execution isolation](https://www.freedesktop.org/software/systemd/man/258/systemd.exec.html),
[cgroup resource control](https://www.freedesktop.org/software/systemd/man/latest/systemd.resource-control.html),
and [transient service launching](https://www.freedesktop.org/software/systemd/man/258/systemd-run.html).


### Built-in validation evidence — 2026-11-15

The isolated snapshot based on `d0b252c` passed **79 distinct focused Java cases**, zero
failures/errors: 71 CLI command/workspace/resource cases and eight fake-HTTP command integration
cases through the existing public Claude/DeepSeek facades. Two explicit Linux-only acceptance
cases were skipped on this macOS host. After the final shutdown-identity correction, the 20
affected framing/registry/backend cases and eight facade cases passed again; unchanged focused
results were reused. The backend fixture also exports its two-class launcher jar and runs it in
a constrained JRE-only child: EOF rejects execution with the fixed failure status, with no
application classpath or payload. The child is waited for and terminated in test cleanup.

Checkstyle, licenses, strict Javadocs, sources and publish-profile shaded packaging passed.
The final shaded artifact passed the published `0.164.0-1` descriptor/shape/shading/checksum gate
and all existing packaged JDK-only consumers. A separate comparison against `d0b252c`'s verified
artifact checked **148 existing engine/tool/SPI/SDK types**: every descriptor, record component,
enum value/order and sealed shape is identical, with no additions to an existing interface.
Only the new built-in factory and internal implementation are added.

Final shaded artifact SHA-256: `db9b611824ecd091e3d2d13eec9d23c761165bb9c2bb4172e6bf614fa742cfec`.

Maven ran offline, serially, at low priority, with one active CPU, one test fork, a 512 MiB heap,
SerialGC and JUnit parallel execution disabled. No POM/dependency file changed. All four unrelated
Claude CLI edits were excluded from the tested snapshot and verified byte-for-byte unchanged.
No agents, Docker, downloads, live inference, provider CLIs, broad/stress or Bytequay suites,
Xcode builds or simulators ran. The Linux enforcement and crash-injection acceptance gate remains
open; fake-service tests and this macOS JRE smoke test do not prove real Linux containment.

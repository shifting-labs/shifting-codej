# Built-in tools for the shared API engine

This is an additive, opt-in capability of `ApiAgentDriver`, shared by Claude API and DeepSeek API.
Existing APIs, reference encodings, CLI behavior, and text/host-tool defaults are unchanged.
`CodingTools` is an immutable record with validated resource limits. Provider adapters still own
only inference; they receive schemas and tool results, never filesystem/process handles.

## Enablement and permissions

```java
Agent agent = ClaudeApi.builder()
        .model(modelId)
        .apiKey(() -> secretStore.read("anthropic"))
        .codingTools(CodingTools.defaults())
        .build();
SessionOptions options = SessionOptions.in(workspace)
        .access(Access.WORKSPACE_WRITE) // READ_ONLY withholds apply_patch
        .approvals(hostApprovalHandler)
        .build();
try (agent; Session session = agent.startSession(options)) {
    session.run(Input.text("Inspect the code and propose a small fix"));
}
```

`DeepSeekApi.Builder` and `ApiEngineConfig.Builder` expose the same additive `codingTools(...)`
setting. Enabling it does not start a process at construction. `validate()` is side-effect-free.
Opening a coding session establishes an exclusive workspace lease and probes the OS sandbox;
there is no inference, provider CLI, or unsandboxed fallback during that probe. Keep state and
profile folders outside the workspace where practical; they are excluded from built-in reads.

| Session access | Capabilities |
| --- | --- |
| TEXT_ONLY | No tools |
| TOOLS_ONLY | Existing trusted host callbacks |
| READ_ONLY, coding enabled | File reads/listing, literal search, contained commands |
| FULL / public WORKSPACE_WRITE, coding enabled | The above plus single-file patches |

Every built-in call requires ALLOW_ONCE, including when host tools are configured to auto-run.
Approval does not widen access or the fixed sandbox; scoped grants are refused. Extra writable
roots, network access, command exemptions, custom sockets, review/publication policies, and
custom command/read denial lists are rejected until their enforcement is implemented.
`approveEveryCommand`, `approveEveryEdit`, and `fixedSandbox` are already satisfied by this mode.
Existing host `Tool` callbacks remain trusted application code, outside built-in containment;
the application must give them authority appropriate to the selected session.

## Typed contracts

All new tool arguments use `Tool.of` and validated records in `ToolMessages`; missing, extra,
null, mistyped, traversal, invalid action/preimage, and excessive inputs fail before effects.
Results and safe failures are records serialized with Jackson. New tools do not read JSON trees.
The existing engine checkpoint format remains in place. Structured results that exceed the
output budget fail explicitly rather than returning truncated, invalid JSON.

| Tool | Main input | Result / contract |
| --- | --- | --- |
| read_file | Relative path, optional 1-based startLine and maximumCharacters (default 4096) | UTF-8 excerpt, whole-file SHA-256, firstLine and truncated flag |
| list_files | Relative directory and limit (1–1000) | Immediate entries and truncation flag; protected entries omitted |
| search_files | Relative directory, literal single-line text, case sensitivity, limit (1–200) | Matching line numbers/previews, byte count and truncation flag; no regex execution |
| apply_patch | Relative path, CREATE/UPDATE/DELETE, optional expectedSha256, typed before/after edits | Single-file result and resulting hash; update/delete require the preimage |
| shell_command | Absolute argv executable, relative directory, positive bounded timeoutSeconds | Exit status/reason, separately bounded stdout/stderr and truncation flags |

CREATE requires one empty-before insertion and no preimage; it uses CREATE_NEW and never
overwrites an existing path. UPDATE validates every exact, unique replacement before effects,
fsyncs a fresh same-directory file, preserves ordinary POSIX permissions, and atomically replaces
the target. DELETE requires the expected digest. Directory changes are fsynced before success.
No multi-file transactions, directory creation, regex patches, or automatic stale-preimage retry.
A crash during CREATE can leave an incomplete new file; its started receipt blocks replay.
SDK workspace leases exclude concurrent coding sessions. Preimage checks are optimistic against
trusted external editors, not a filesystem compare-and-swap transaction with arbitrary writers.

## Filesystem boundary and costs

Descriptor-relative `SecureDirectoryStream` operations use NOFOLLOW at every component. Reject
absolute paths, parent traversal, symbolic links, special files, and multiply linked files.
Protect `.git`, `.hg`, `.svn`, credential/tool state locations, `.codej*`, and configured engine
state/runtime subtrees. Reads are strict UTF-8 and bounded; searches are literal, sequential,
interruptible, and bounded by bytes, entry count, result count, and directory depth.

One metadata-only workspace scan establishes command exclusions for existing hard links,
symlinks, protected directories, and special files. It runs once per session, not per command;
there are no worktree copies or per-file JVMs. Oversized/deep workspaces or exclusion profiles
fail closed. New SDK patches cannot introduce links or protected paths. Concurrent malicious
same-user host processes are outside the boundary; they already have the application's authority.

## Command containment and lifecycle

The first implementation supports macOS Seatbelt through the fixed `/usr/bin/sandbox-exec`.
Other platforms, a missing/disabled sandbox, or an invalid profile fail closed. Apple deprecates
sandbox-exec, so the runtime check and native regression tests are required; another platform
backend is future work, not a silent downgrade.

The policy denies by default: no network, IPC service access, fork/spawn, or hard-link creation.
It permits one process to exec, narrow system runtime reads, workspace reads, and private scratch
writes. The workspace stays read-only even in WORKSPACE_WRITE; all edits use apply_patch. Denied
workspace paths stay unreadable through aliases. `/System/Volumes/Data` is not a system read root.
The child environment is cleared and rebuilt from a fixed allowlist; HOME/TMPDIR point at private
scratch, and API keys/provider environment variables are not inherited.

Commands may use absolute argv directly, or `/bin/sh -c 'exec ...'`. Pipelines, child processes,
daemons, general build toolchains, and provider CLIs are outside this first contract. Bounds
cover elapsed time and captured output; this is not a CPU/memory or scratch disk quota system.
Scratch cleanup is no-follow, bounded, and only occurs after verified process termination.

Reuse the borrowed `ProcessSupervisor`; never close it. Persist the prepared launch ID in a typed
journal entry before releasing its start gate. Persist tool outcomes before acknowledging launch
markers. Fork denial makes root exit proof that sandbox work finished; the supervisor still
verifies runtime cleanup. Cancellation requests stop and waits for verification. Unverified
termination or uncertain file durability leaves the started receipt unresolved, blocks replay,
and retains workspace/session ownership until shutdown can be confirmed. Reopening does not
silently rerun an uncertain operation.

## Implementation checklist

- [x] Add opt-in immutable settings and facade/connector wiring; preserve old API shapes/defaults.
- [x] Define validated record requests/results and safe structured errors.
- [x] Add direct bounded file/list/search and preimage-checked single-file patch operations.
- [x] Enforce read-only access, protected metadata, no-follow containment, and workspace leases.
- [x] Add contained single-process commands using existing durable process supervision.
- [x] Require approval without allowing approval to widen permissions.
- [x] Preserve unresolved receipts and ownership when effects/termination are uncertain.
- [x] Pass focused boundary, native sandbox, engine and facade tests with one CPU/test fork.
- [x] Package the shaded artifact and run backward-compatibility consumer checks.
- [x] Review the final diff, preserve unrelated edits, and commit.

## Validation evidence — 2026-11-13

196 distinct cases passed across 19 selected classes: 8 API, 66 shared CLI/engine/SPI, and 122 SDK
adapter/connector/facade cases. The changed filesystem and native sandbox suites were rerun after
the final containment corrections; the remaining focused results were retained. All cases passed
with no skips. Checks include aliased/future private-state roots, future protected metadata,
workspaces beneath `.codex`, prepared-launch ordering, cancellation, uncertain shutdown, approval
denial, read-only access, atomic replacement/preimages, typed schema binding, and facade HTTP replay.

Checkstyle, license checks, strict public Javadocs, sources and shaded packaging passed. Processes
used one CPU, a 512 MiB heap, serial GC, low priority, one test fork, and disabled JUnit parallelism.
Builds ran in an isolated source snapshot excluding the four pre-existing Claude CLI edits.

Final candidate SHA-256: `530255b81857733d1ef9cc559fe97af54cc433d6157d2f9a03ea7c31d4ed9f3e`.
The published 0.164.0-1 descriptors, record/enum/sealed shapes, constants, checksum resources and
unchanged baseline-compiled consumer remained compatible. Candidate-only JDK + shaded jar fixtures
passed for Claude API, DeepSeek API and coding-tool file reads/native sandbox startup/durable resume.
No release or default Maven-cache replacement was performed.

No agents, live-provider inference, broad/stress suites, Xcode, simulator, or new Bytequay suites
were started. Earlier downstream evidence remains recorded in the parent design; it is not a
claim that those suites were rerun for this milestone. Shell limits above remain deliberate.

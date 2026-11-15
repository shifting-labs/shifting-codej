# Opt-in audit and storage retention

Status: implemented and verified. Extends
[context compaction](context-compaction.md) without changing existing public API signatures,
record components, session references, permissions or default storage behavior.

## Explicit ownership and deletion

`AuditRetentionPolicy` is a borrowed, thread-safe host selector. Install it through
`auditRetention(...)` on `ApiEngineConfig`, either adapter config, or the public `ClaudeApi` /
`DeepSeekApi` builder. No policy is installed by default. It receives a private immutable
`AuditRetentionContext`: turn ID, currently retained canonical history, optional committed summary
boundary, deadline and live cancellation. Callbacks perform no inference, I/O or tool effects; the
engine never closes the service.

Return empty to decline, or `AuditRetention(retainExchanges, maximumAttachmentDeletions)`.
`retainExchanges` is a nonnegative floor, measured in inference exchanges. Complete tool groups and
uncovered data can retain more. Attachment deletion is separately disabled at zero; positive values
are bounded to 1–1,024 files per cleanup pass. A convenience policy captures an immutable plan:

```java
AuditRetentionPolicy retention = AuditRetentionPolicy.keepLatest(8, 128);
// ClaudeApi.builder().contextSummarization(summaryPolicy).auditRetention(retention) ...
// DeepSeekApi.builder().contextSummarization(summaryPolicy).auditRetention(retention) ...
```

This is irreversible logical audit removal, not archival or secure erasure. It applies only to the
current engine-owned session checkpoint and its private staged attachments. It never removes
worktree files, host originals, other sessions, arbitrary tool artifacts, locks or session directories.

## Safe prefix boundary

Pruning runs only in `READY`, with no outstanding/uncertain receipts. It does not change `REQUEST`,
`TOOLS` or `BLOCKED` recovery state. The engine reads provider-owned `ModelCompactionMemory` locally;
only a settled canonical prefix covered by that committed summary can be discarded. Missing memory,
an unknown legacy gap, or uncovered history leaves those facts intact. There are no implicit model
calls to make data eligible for retention.

The cut cannot split a tool invocation/result chain. The remaining suffix starts with a user
exchange, or is empty. The matching journal request/response/tool data is removed with the prefix;
`CodingLaunch` and `PreparedResource` ownership facts remain, and a fresh input awaiting pending-tool
recovery is protected even when its journal entry precedes a recovery response. Native replay,
permissions, tool definitions and current execution authority are unchanged.

`ModelHistory.retained(throughRequestId, exchanges)` records the inclusive discarded boundary.
`complete()` becomes false because the original raw prefix is gone, while the additive
`summarizedThroughRequestId()` distinguishes this known summary-backed prefix from an unknown legacy
gap. The factory validates a complete retained tool chain and rejects duplicate boundary IDs. Append
preserves the anchor. A `ModelRequest` carrying retained metadata requires native replay and a fresh
request ID; the engine validates that the provider's memory covers the anchor before continuation.

Incremental summarization merges prior memory with new retained exchanges. It accepts an anchored
empty suffix and does not regenerate an already-covered prefix. Retention can commit between chunks;
later budget exhaustion or failure resumes from the new boundary. Prompt contributors, policies and
model clients see the explicitly shortened history and anchor. Host policies that require complete
raw history must decline retention or adapt to this metadata; a local host-prepared summary must
preserve earlier memory itself. The shared LLM summarizer does that merge automatically.

## Commit and cleanup order

The policy runs after pending-tool recovery, after committed summary chunks, before fresh prompt
capture and after a settled conversation response. A candidate checkpoint is validated, atomically
replaced and fsynced before its in-memory snapshot is published. A failed retention commit damages
the live session under the existing writer rules; attachment cleanup never starts first. The last
acknowledged checkpoint remains the recovery source.

Cleanup uses only the committed checkpoint's attachment references plus protected in-flight fresh
attachments. It streams the owned `attachments` directory, considers only canonical UUID-named
regular files, and uses secure directory-relative operations with no symlink following. Referenced
files, symlinks, nested directories and unexpected names are preserved. Each deletion pass obeys
its configured count and checks cancellation between candidates. If secure directory operations
are unavailable, cleanup fails without falling back to an unsafe traversal.

Deletion follows the durable reference removal, so a crash can leave unreferenced files for a later
pass without making a committed attachment reference dangle. A cleanup failure reports a failed
turn but does not undo the pruned checkpoint or mark its replay uncertain; it remains resumable.
Already completed deletions are not rolled back. The metadata-only `AuditPruned` observation reports
removed exchange/journal counts and successful attachment deletions after the operation succeeds.
Observers are best effort, not a durable archive or proof that no commit happened after cancellation.

## Storage compatibility and bounds

Default writes remain checkpoint **format 2**. The first actual audit prefix removal opts that
session into **format 3**, adding only the optional retained boundary to typed history metadata.
Formats 1 and 2 retain their existing migration/default behavior. Strict decoding rejects version /
anchor mismatches, malformed metadata and inconsistent response journals. Version 3 remains readable
by current code with retention disabled; its native replay format and public session reference are
unchanged. Earlier binaries reject format 3, so automatic downgrade cannot recover deleted facts.

The configured checkpoint-size bound still applies. Retention has a safety floor: native replay,
uncovered exchanges, pending/unsent facts, recovery ownership metadata and referenced attachments
can prevent a requested size from being reached. Configure it before reaching the existing hard
storage limit; opening an already oversized checkpoint still requires a sufficient limit. The
feature does not delete whole old sessions, enforce time-based expiry, archive data, or guarantee
forensic erasure of replaced files or backups.

## Checkbox plan

- [x] Add the optional policy, typed plan/context, immutable builder wiring and metadata observations.
- [x] Add explicit retained history anchors and strict opt-in format-3 persistence.
- [x] Preserve pending execution, complete tool groups, fresh intent and recovery ownership facts.
- [x] Reuse provider memory for continued incremental summarization after pruning and restart.
- [x] Delete only unreferenced owned attachments after commit, with count/cancellation/containment checks.
- [x] Add baseline SPI, failure/cancellation, migration, cleanup and provider fake-HTTP tests.
- [x] Verify focused tests, packaged usage and API/storage compatibility; record results and commit.

## Validation

The isolated snapshot based on `0de8e0f` passed **345 focused Java cases**, zero failures, errors or skips:

- **210 CLI cases**: engine (102), retention transformation/SPI values (5), session store/cleanup (8),
  checkpoint migrations (25), codec (33), summary encoding (4), model history (6), compaction values (4),
  memory values (1), model SPI values (17) and extension observations (5).
- **135 SDK cases**: each public facade (13 each), Anthropic/DeepSeek clients (21/20), shared connector
  contract (9), context compaction/summarization/retention (16), each API connector (17 each), replay
  codec (5) and session-reference compatibility (4).
- **Five Python compatibility-checker controls** passed.

Coverage includes safe tool-group floors, unknown and uncovered history, retained anchors and malformed
version metadata, fresh intent before a recovery response, preserved resource ownership, cancellation,
failed commits before cleanup, cleanup failure after commit, bounded secure attachment deletion,
referenced/in-flight files, symlinked entries/directories, zero retained exchanges, incremental chunk
progress with pruning, budget exhaustion and restart. Both providers continue after pruning and after
disabling retention. The new request guard rejects retained metadata without native replay.

The published `0.164.0-1` binary consumer, shading/resource checks and all packaged fixtures passed.
`AuditRetentionConsumer` uses only the JDK and shaded jar, pruning between bounded summary chunks
through both public facades, then resuming format 3 with retention disabled. Its request counts
remain six conversation and three summary calls per provider: retention adds no inference.
A separate comparison against the preceding artifact checked **127 existing types**: old descriptors,
record components, enum order and sealed shapes are unchanged. Existing types gained only five builder
setters and the two `ModelHistory` methods. The preceding artifact was also exercised as a reader:
it accepts the unchanged format-2 fixture and explicitly rejects the new consumer's format-3 file.

Final shaded artifact SHA-256:
`675c28d56c308ddca470814129c53e2703172361ed96791668a3736ec0c770d4`.

Maven ran offline with `-T1`, one Surefire fork, disabled JUnit parallel execution, low process priority
and `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`. Two new round-trip
assertions were corrected to compare serialized values rather than replay object identity; affected
cases were rerun, reusing the other passing results. Final request-guard/provider cases passed on the
packaged source. Strict style, license, Javadocs and shaded packaging passed. Four unrelated Claude
CLI edits are excluded from the snapshot and commit and preserved byte-for-byte. No agents, broad
suites, Bytequay tests, live providers, provider CLIs, native builds or simulators ran. The exercised
contracts do not establish LLM fidelity, forensic erasure or a performance benchmark.

# Provider-neutral conversation history and checkpoint migrations

Status: implemented and verified. Extends the [engine separation plan](deepseek-harness-study.md)
without changing existing public sessions, turns, connectors, record shapes or reference versions.

## Conversation boundary

`ModelHistory` is the provider-neutral, immutable prefix of settled inference exchanges. Each
exchange stores the local request ID, provider/model provenance, actual submitted `ModelInput`,
displayable assistant text, complete tool calls and observed stop reason. It excludes provider-private
reasoning, signatures and native protocol objects. Input, text, arguments and attachment references
are still sensitive data, stored with the existing private checkpoint permissions and size budget.

`ModelRequest.history()` and its builder setter are additive. Existing clients see an absent default
and retain their existing replay behavior; the shared engine supplies a snapshot preceding the new
`ModelRequest.input()`. Clients must not append that input twice. No mandatory `ModelClient` method,
existing sealed subtype, enum member or public record component changes.

The engine records a new exchange atomically with its observed response, private replay and tool
receipts. Input queued by a public session is not yet model history. This matters when a fresh user
message arrives while an old pending tool batch must first be reconciled. Failed or uncertain calls
retain the existing request intent and reconciliation gate rather than inventing assistant output.
Tool results remain durable execution receipts until submitted and settled in a subsequent exchange.

History validates exact ordered tool-call/result pairing between exchanges. It rejects duplicate
request IDs, missing/reordered/extra tool results, and fresh user input while known calls are pending.
Persistence validates known response IDs, stop reasons, completeness and final model provenance
against the execution journal; ordinary immutable reads do not revalidate the entire prefix.
The current request gets the same validation before inference. Cancellation cannot erase an already
observed response or cause a committed tool effect to execute again.

Native Anthropic and DeepSeek adapters continue using their lossless private replay. Canonical
history is available to SPI implementations for inspection and future request projection. This step
does not add provider switching, reinterpret opaque replay, import signed reasoning into another
provider, or promise that a neutral transcript alone reproduces native continuation.

## Versioned persistence

The current checkpoint format is **2**. Format **1** remains readable through an explicit pure
migration in `ApiCheckpointMigrations`; unsupported future versions fail closed. Connector/profile/
lane ownership and phase/receipt validation remain mandatory. Public `AgentSessionRef` contract
version stays **1**: storage format and public reference versions are independent.

Version 1 omitted assistant text and complete historical calls. Migration preserves its execution
journal, setup, input, receipts and opaque replay exactly and starts a known empty suffix. It marks
history incomplete whenever the legacy state may contain earlier inference. A pristine legacy
session can have complete empty history. Migration never reconstructs conversation facts from UI
events, unsent user journal entries or guessed provider payload fields. New exchanges extend the
suffix without erasing the missing-prefix marker.

Decode/migration is read-only. The repository acquires its single-writer lease, decodes, migrates
in memory and validates ownership before exposing state. The original file is unchanged until a
normal successful checkpoint commit atomically writes version 2. Invalid/foreign/oversized states
never trigger migration writes. A failed write leaves the live session damaged under the existing
uncertainty rules and cannot acknowledge uncommitted conversation history. Closing preserves the
last committed file and releases the writer only after local work is confirmed stopped.

Migration is idempotent: decoding a current checkpoint performs validation without backfilling or
duplicating history. A version-1 file carrying version-2 history and a version-2 file missing history
are invalid. Earlier binaries cannot read format 2; automatic downgrade is not supported. Preserve
the old state externally before rolling back an application version that has committed format 2.

The codec binds strict typed records; JSON handling stays at the persistence boundary. History
shares immutable snapshots across request/phase changes. Full checkpoint serialization still costs
O(checkpoint size), and the unchanged configured size bound includes both native replay and neutral
history. No background persistence, additional executor, compaction or silent truncation is added.

## Plan

- [x] Add immutable history SPI values, additive request access and baseline value/consumer tests.
- [x] Capture actual settled inputs, assistant text and tool calls with private replay and receipts.
- [x] Add explicit format-1 to format-2 migration with honest incomplete-prefix metadata.
- [x] Preserve ownership, pending-batch recovery, request uncertainty and atomic save behavior.
- [x] Test old/current fixtures, malformed versions/history, partial prefixes, restart and cancellation.
- [x] Run focused engine/model/adapter/facade tests with one fork and bounded JVM resources.
- [x] Verify existing descriptors/shapes and packaged consumers, preserve unrelated edits, and commit.

## Validation

The final isolated source snapshot passed **256 focused cases**, zero failures/errors/skips:

- CLI/model values and engine: 153 cases across `TestModelHistory`, `TestModelSpiValues`,
  `TestApiAgentDriver`, `TestApiCheckpointCodec`, `TestApiCheckpointMigrations`,
  `TestApiSessionStore`, `TestToolDispatcher` and `TestApiToolProviders`.
- SDK integration: 103 cases across the external `TestModelClientSpi` consumer, direct Anthropic/
  DeepSeek model clients, API connector baselines, both API facades, and session-reference compatibility.
- Checkstyle, licenses, strict Javadocs and published-style shaded packaging passed.
- The published `0.164.0-1` compatibility checker passed unchanged baseline-compiled consumers,
  signatures/type shapes, resource/shading checks and packaged fake-HTTP/coding/provider fixtures.
  The packaged provider example now reads and validates canonical history using only the JDK and jar.
- Comparing the preceding `2ecd2ea` artifact against 84 existing connector/model/tool/resource/facade
  types found every existing descriptor and record/enum/sealed shape unchanged. The only reviewed
  additions to existing types are `ModelRequest.history()` and `ModelRequest.Builder.history(...)`.
- Both format fixtures, pure/idempotent upgrades, missing-prefix markers, ordered tool results,
  cancellation/restart, unknown versions, ownership rejection, writer release and failed writes
  are covered. No queued user or opaque native replay content is guessed into canonical history.

Maven used offline mode, `-T1`, one test fork, disabled JUnit parallel execution and
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`, at low priority.
The tested shaded candidate SHA-256 is
`c2e5aad088aa3b9c3cad5a530ea111e8dee953e04125b4293fca97642aba66ec`.
The temporary source snapshot excluded the four unrelated Claude CLI edits, whose hashes remain
unchanged. No agents, live inference/provider CLIs, broad application/Bytequay suites, Xcode builds
or simulators were started. No performance benchmark or automatic cross-provider migration is claimed.

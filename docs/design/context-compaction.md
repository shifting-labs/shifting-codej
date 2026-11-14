# Opt-in context compaction

Status: implemented and verified. Follows [conversation history](conversation-history.md),
[prompt contributors](prompt-contributors.md) and [tool scheduling](tool-scheduling.md).

## Boundary

Compact model context, preserving the full durable conversation history, execution journal and
tool receipts. `ContextCompactor` is an explicitly installed, borrowed, thread-safe host service.
It proposes a bounded `ModelCompaction` containing summary text and a retained exchange count.
The callback performs no inference or tool effects. Automatic LLM summarization, token estimation,
audit retention and storage compaction are separate follow-ups; no hidden model calls are added.

The engine runs proposals once per new public turn, after reconciling pending tools and before
capturing fresh prompts. Empty sessions skip the callback. Incomplete legacy history cannot be
compacted. A boundary must summarize at least one settled exchange and cannot split tool calls from
their ordered results; retained context starts with a user exchange and ends without pending calls.
The immutable full history remains the `ModelRequest.history()` and prompt-contributor view.

`ModelClient.compact(ModelCompactionRequest)` is an additive default returning unsupported. Provider
adapters own the replay rewrite, preserve retained private blocks/signatures, and encode the summary
as user-role context. An explicit proposal against an unsupported adapter fails before inference.
Compaction cannot change instructions, model selection, tools, approvals or permissions.

## Persistence and ownership

Proposals and rewrites run on the existing worker, respecting turn cancellation and deadlines. Check
stop before/after callbacks and before committing. Returned replay must have the same provider owner,
fit existing limits and reduce encoded replay characters. An identical state is an idempotent no-op,
so retrying after a definitely rejected inference does not force repeated compaction. Commit the
replacement synchronously before inference. Failure before save keeps the original replay; a failed save retains writer ownership and
damages the live session as usual. A cancellation racing a successful save keeps the committed state.

Checkpoint format 2 remains unchanged: it already stores full history and opaque versioned replay.
Compacted native replay uses provider format 2, wrapping format-1 native messages plus a summarized
boundary ID. This prevents a later proposal from claiming native content already removed. New
requests retain the boundary metadata. Version-1 native replay stays readable and unchanged when
compaction is disabled. Older adapters reject the opt-in version-2 replay rather than misreading it.
Public session references and existing public record/sealed/enum shapes remain unchanged.
Disabling the policy with current adapters still permits resume of compacted sessions. A later
proposal can move the boundary forward, but cannot restore private native context already removed.

Known credentials must not enter replay through a summary. Notifications contain only metadata,
never summaries, message content, reasoning or signatures. Compaction reduces provider context and
encoded replay characters; it does not remove full audit history or lift the checkpoint's storage bound.

## Host integration

Install a `ContextCompactor` through `ApiEngineConfig.Builder.contextCompactor(...)`,
`ClaudeApi.Builder.contextCompactor(...)` or `DeepSeekApi.Builder.contextCompactor(...)`.
The adapter configs expose the same additive setter. No compactor is installed by default.

The callback receives immutable full history, the current model, live cancellation/deadline state
and `replayCharacters()` (UTF-16 characters, not tokens). Return `Optional.empty()` to continue
unchanged, or a validated `ModelCompaction(summary, retainExchanges)` using a host-prepared summary.
Incomplete migrated history may be inspected and declined, but cannot be compacted. The engine
does not close the borrowed service. Implementations must support concurrent sessions, cooperate
with cancellation, and avoid inference or tool effects inside the callback.

The engine and each provider validate the request independently. The replay wrapper retains only
an opaque native suffix and its boundary ID; provider-private fields stay at the adapter boundary.
Known credentials are rejected, and `ContextCompacted` observations expose only exchange counts.
The proposal summary is bounded to 65,536 characters and remains subject to the engine text limit.

Usage and failure examples are exercised by `TestApiAgentDriver`, `TestApiContextCompaction` and
the JDK-only [packaged consumer](../../scripts/compatibility/ContextCompactionConsumer.java).

## Checkbox plan

- [x] Add validated proposal/request records, borrowed compactor context and optional model-client SPI.
- [x] Add engine capture/validation, durable replay replacement and metadata observation.
- [x] Wire opt-in configuration through both API configs and public facade builders.
- [x] Implement local adapter compaction preserving retained Claude/DeepSeek native data.
- [x] Add baseline SPI, engine failure/recovery, repeated compaction and fake-HTTP consumer tests.
- [x] Run focused tests and packaged/API compatibility checks with bounded JVMs and one test fork.
- [x] Reconcile the engine plan, preserve unrelated edits and commit.
- [ ] Evaluate explicit engine-budgeted LLM summarization and independent audit/storage retention later.

## Validation

The isolated source snapshot passed **341 focused Java cases**, with zero failures, errors or skips:

- **223 CLI cases**: engine (69), checkpoint codec (33), migrations (25), session store (6), tool
  providers (24), parallel dispatcher (9), dispatcher (17), extension SPI (3), compaction values (4),
  history (5), model values (17), prompt values (4) and execution budget (7).
- **118 SDK cases**: public Claude/DeepSeek facades (13 each), Anthropic/DeepSeek clients (21/20),
  shared connector contract (9), compaction across both providers (6), each API connector (14 each),
  replay codec (4) and session-reference compatibility (4).
- **Five Python compatibility-checker controls** passed.

Coverage includes durable replacement before inference, decline/unsupported/invalid proposals,
interruption, noncooperative callback ownership, failed checkpoint saves, definitive-inference retry,
legacy incomplete history and pending-tool recovery. Provider tests retain signed thinking/reasoning
and ordered multi-tool result chains, reject malformed wrappers, and check repeated compaction and
resume without the policy. Local compaction makes no HTTP request.

The published `0.164.0-1` binary consumer and signature/shape checks passed against the shaded jar.
The new JDK-only consumer exercises both public facades, repeated compaction and durable resume,
with five real fake-HTTP inferences and exactly two local compactions per provider. An additional
comparison against the preceding engine artifact checked **118 existing types**: every old descriptor,
record component, enum order and sealed shape is unchanged. Only five builder setters, the default
model-client method and its two provider overrides were added to existing types.

Candidate shaded artifact SHA-256:
`a61f3e500fdd82a81f661bc3747d2d4a5cce27ce08c01b0d37b528a635669e87`.

Validation used an isolated snapshot excluding the four unrelated Claude CLI edits. Maven ran
offline with `-T1`, one Surefire fork, JUnit parallel execution disabled, low process priority and
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`. The CLI results were
reused when rerunning only the SDK tests after fixing an assertion helper. Strict style, license,
Javadocs and shaded packaging passed; the final cosmetic private constant rename was packaged with
tests skipped. No agents, broad suites, Bytequay tests, live providers, provider CLIs, native builds
or simulators were started. This verifies the exercised contracts, not all downstream behavior or
performance; storage retention and automatic summary quality remain outside this step.

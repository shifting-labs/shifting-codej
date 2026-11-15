# Opt-in context compaction

Status: implemented and verified. Follows [conversation history](conversation-history.md),
[prompt contributors](prompt-contributors.md) and [tool scheduling](tool-scheduling.md).

## Boundary

Compact model context, preserving the full durable conversation history, execution journal and
tool receipts. `ContextCompactor` is an explicitly installed, borrowed, thread-safe host service.
Separately installed [audit retention](audit-retention.md) can discard covered audit prefixes and
unreferenced staged attachments; compaction alone continues to preserve them.
It proposes a bounded `ModelCompaction` containing summary text and a retained exchange count.
The callback performs no inference or tool effects. An alternative `ContextSummarizationPolicy`
explicitly opts into an engine-budgeted LLM summary. [Provider-aware context budgeting](context-budgeting.md)
adds optional input sizing without guessing token counts;
audit/storage retention is a separate opt-in policy. No policy is installed by default.

The engine runs proactive proposals once per new public turn, after reconciling pending tools and before
capturing fresh prompts. Empty sessions skip the callback. Incomplete legacy history cannot be
compacted. A boundary must summarize at least one settled exchange and cannot split tool calls from
their ordered results; retained context starts with a user exchange and ends without pending calls.
The immutable full history remains the `ModelRequest.history()` and prompt-contributor view unless
the host explicitly enables retention, which exposes a retained suffix and validated summary anchor.
With context budgeting enabled, a measured oversized fresh request can invoke the installed policy
again with `contextAssessment()` metadata, then be rechecked before submission. Contributors are not
recaptured, and pending tool-result batches are never compacted by this path.

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

## Engine-budgeted LLM summaries

`contextSummarization(ContextSummarizationPolicy)` is additive on the engine, both adapter configs
and both public API facade builders. The borrowed policy receives the same immutable context as a
local compactor and returns `Optional<ContextSummarizationPlan>`. Its unchanged two-component record
selects `retainExchanges` and `maximumOutputTokens`; an empty result declines. Installing both modes
is rejected at build time. Growing prefixes use the bounded incremental path below.

For example, a host may use its own character threshold (not a token estimate):

```java
ContextSummarizationPolicy policy = context -> {
    if (!context.history().complete() || context.replayCharacters() < 100_000
            || context.history().exchanges().size() < 4) {
        return Optional.empty();
    }
    // The host must select a suffix beginning at a user exchange, keeping tool groups intact.
    return Optional.of(new ContextSummarizationPlan(0, 1024));
};
// ClaudeApi.builder().contextSummarization(policy) ...
// DeepSeekApi.builder().contextSummarization(policy) ...
```

After recovery and before fresh prompt capture, the engine validates the complete prefix/tool
boundary and the adapter's additive `supportsContextCompaction()` opt-in. The default is false,
preserving existing custom clients and local compaction behavior. It then encodes just the selected
canonical prefix as typed JSON data. No private reasoning, native replay, fresh input or session
instructions enter this transcript. Attachment names/media types are metadata; paths and bytes are
excluded, and no files are opened. A bounded writer rejects oversized escaped JSON before submission.

Each isolated `ModelClient.generate` call uses the current model, effort, service tier and
remaining turn deadline, fixed summary instructions, empty history, no replay and no tools. The
transcript is untrusted context data. The engine counts this call against `maximumRequests` and
caps its output by the plan, engine output limit and remaining observed token budget. Admission
reserves a further request slot and at least one output token for continuation. Observed input usage
can still exhaust the turn; missing usage stays unknown. There is no pre-submission input-token or
billing guarantee, automatic retry, new client, executor or worker pool.

Summary deltas count against the turn's text budget without emitting message events. Final usage
is charged once and emitted through the existing usage event. The additive metadata-only
`ContextSummaryCompleted` observation identifies a received summary response, including rejected
stop reasons; `ModelCompleted` retains its existing durable-conversation meaning. A summary must be
nonblank, bounded, completed, free of tool calls and known credentials. Refused, truncated, oversized,
tool-bearing or budget-exhausting responses do not reach the replay rewrite. The successful text
becomes a regular `ModelCompaction` and follows the same validation and synchronous commit path.

The synthetic request/response is not a conversation exchange, native continuation or tool receipt.
Its transient replay is discarded. Failure or uncertain delivery leaves the last committed checkpoint
usable, since this call has no tool effects or conversation continuation to reconcile; a later
explicit turn may pay for another summary. Cancellation retains the session writer until the
adapter's completion fence confirms it stopped. Only validated replay replacements are committed.
There is no new checkpoint format or persistence migration.

Summary generation does not restore removed native blocks, guarantee summary fidelity, or reduce full
audit storage. Native-boundary and reduction checks still apply after generation, so a provider
rejection can consume summary budget without changing replay.

## Bounded incremental summaries

`maximumSummaryInputCharacters(int)` is additive on the same five builders. Its positive default
is **65,536 UTF-16 characters**, capped further by the engine's existing text limit. It bounds the
encoded JSON transcript, including previous summary memory, JSON escaping and the selected new
exchanges. It is not a token estimate or a limit on the entire provider request envelope.

The additive `ModelClient.compactionMemory(ModelReplayState)` default returns empty. Claude and
DeepSeek recover a validated `ModelCompactionMemory(throughRequestId, summary)` from their existing
format-2 native summary message and boundary envelope. The engine never reads provider JSON.
No private format or checkpoint version changes, duplicate summary field or new persisted engine
state is needed. Local host summaries can become incremental memory too. Custom adapters may retain
full-prefix fallback through the default; they must implement memory recovery to reuse their own
committed summary across sessions.

The engine validates the recovered request ID against full canonical history and rejects backward
or unsafe tool boundaries before inference. An already-covered target needs no summary call. The
target is the full history prefix preceding the policy's retained suffix. For each step, the engine:

1. Encodes prior memory and the largest fitting prefix of previously unsummarized exchanges. A lazy
   immutable view and bounded writer limit failed size probes; binary search finds the character
   boundary without materializing the entire growing prefix.
2. Moves the cut back to a settled user boundary, keeping every tool-call/result chain together.
   If prior memory and the next indivisible group cannot fit, it fails before inference rather
   than trimming content, dropping a tool result or sending an oversized input.
3. Runs one isolated summary inference using the existing shared turn budgets, then validates and
   synchronously commits a reducing provider replay rewrite. The new summary becomes the next
   chunk's memory; already summarized raw history is not sent again.
4. Repeats until the requested boundary is reached, then captures the fresh prompt and continues
   the user conversation. Every call reserves a request slot for continuation. The existing
   request/token/text/deadline limits bound the loop; no extra workers or automatic retries exist.

A later failure, cancellation or exhausted budget preserves earlier committed chunks. The public
turn reports that outcome and does not submit fresh input until the requested boundary is reached.
After reopening, provider memory resumes at the last committed boundary. Full canonical history,
journal and tool receipts remain untouched. A repeated proposal covering that same boundary is a
no-op, so retrying after a definitive fresh-inference rejection does not regenerate that summary.

An individual oversized message/tool chain or a prior summary consuming the entire input allowance
requires a larger configured limit or a different host plan; this step does not split or truncate
individual records. Full audit storage still has its independent checkpoint bound. Repeated lossy
summaries can lose facts; neither this algorithm nor fake-client tests establish LLM fidelity.

## Checkbox plan

- [x] Add validated proposal/request records, borrowed compactor context and optional model-client SPI.
- [x] Add engine capture/validation, durable replay replacement and metadata observation.
- [x] Wire opt-in configuration through both API configs and public facade builders.
- [x] Implement local adapter compaction preserving retained Claude/DeepSeek native data.
- [x] Add baseline SPI, engine failure/recovery, repeated compaction and fake-HTTP consumer tests.
- [x] Run focused tests and packaged/API compatibility checks with bounded JVMs and one test fork.
- [x] Reconcile the engine plan, preserve unrelated edits and commit.
- [x] Add an opt-in typed summary policy and plan without changing existing compactor/client contracts.
- [x] Reuse the engine's model-call ownership, request/token/text/deadline budgets and usage events.
- [x] Isolate summary transcripts and outputs from tools, answers and canonical history.
- [x] Wire both API providers and add fake-client, fake-HTTP and packaged consumer coverage.
- [x] Verify the focused summary tests and compatibility checks, record results, and commit.
- [x] Add typed provider memory recovery without changing replay/checkpoint formats or record shapes.
- [x] Add configurable encoded input bounds and complete-group incremental chunk selection.
- [x] Commit each bounded chunk and resume after budget exhaustion without repeating raw history.
- [x] Verify incremental engine, provider, packaged consumer and compatibility tests; record results and commit.
- [x] Add independent opt-in [audit/storage retention](audit-retention.md), summary-backed history anchors
      and post-commit owned attachment cleanup.
- [x] Add opt-in [provider-aware context budgets](context-budgeting.md) and pressure-triggered sizing.

## Local-compaction validation (preceding stage)

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

## LLM-summary validation

The isolated snapshot based on `44d7486` passed **272 focused Java cases**, zero failures, errors
or skips:

- **148 CLI cases**: engine (88), summary transcript encoding (2), checkpoint codec (33), model
  compaction values/boundaries (4), model SPI values (17) and extension SPI/observations (4).
- **124 SDK cases**: each public API facade (13 each), Anthropic/DeepSeek clients (21/20), shared
  connector contract (9), context compaction/summarization across both providers (10), each API
  connector (15 each), replay codec (4) and session-reference compatibility (4).
- **Five Python compatibility-checker controls** passed.

Tests exercise capped summary output, shared request/observed-token/text budgets, decline and
unsupported clients, invalid boundaries, bounded escaped input, null/throwing policies, malformed
or rejected summary output, credentials, uncertain delivery, cancellation/deadlines, writer retention
until model quiescence, isolated history/events, repeated compaction and durable resume. Fake HTTP
confirms one extra inference per selected summary and recovery from summary HTTP failure. Attachment
metadata tests open no files. Existing normal tool/recovery and local-compaction behavior also pass.

The published `0.164.0-1` compatibility check passed on the final shaded artifact. The JDK-only
`ContextSummarizationConsumer` uses both public builders and performs five conversation inferences
plus two summary inferences per provider, including restart and policy-disabled resume. The unchanged
baseline consumer and existing candidate fixtures passed. A separate comparison against the preceding
compaction artifact checked **123 existing types**: all old descriptors, record components, enum order
and sealed shapes are unchanged. Existing types gained only five summary-policy builder setters, the
default capability method, its two provider overrides and the static boundary validator.

Final candidate SHA-256:
`da2a5e84020f1f12b3b31af08bd3f131561746b4db2682030bc581e2feae1d83`.

Maven ran offline with `-T1`, one Surefire fork, disabled JUnit parallel execution, low process priority
and `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`. After correcting
test fixtures/imports/style, the passing CLI results were reused for the SDK-only run. Strict style,
license, Javadocs and shaded packaging passed. The tested source excludes the same four unrelated
Claude CLI edits, preserved byte-for-byte and excluded from this commit. No agents, broad suites,
Bytequay tests, live providers, provider CLIs, native builds or simulators ran. These checks establish
the exercised contracts; LLM summary fidelity and live billing remain unverified.

## Incremental-summary validation

The isolated snapshot based on `c8a0878` passed **286 focused Java cases**, zero failures, errors
or skips:

- **157 CLI cases**: engine (94), bounded summary encoding (4), checkpoint codec (33), compaction
  values/boundaries (4), summary memory (1), model SPI values (17) and extension SPI (4).
- **129 SDK cases**: each public API facade (13 each), Anthropic/DeepSeek clients (21/20), shared
  connector contract (9), context compaction/summarization (12), each API connector (16 each), native
  replay/memory codec (5) and session-reference compatibility (4).
- **Five Python compatibility-checker controls** passed.

New cases cover bounded escaped input with prior memory, complete tool groups, indivisible oversized
groups, null/absent/backward memory boundaries, already-covered targets, per-chunk durable progress,
request-budget exhaustion, later uncertain delivery and resume from the committed boundary. Both
real provider adapters recover old format-2 summaries and preserve native tool data. Fake HTTP
exercises a two-chunk prefix, another incremental summary after restart, and omission of earlier
raw history from subsequent summary requests. Existing failure, cancellation and local-compaction
tests remain passing.

The published `0.164.0-1` compatibility check and all packaged consumers passed. The new JDK-only
`IncrementalSummaryConsumer` sets a 2,000-character bound through both public builders. Each provider
performs six conversation requests and three summary requests across growing history and restart;
request bodies verify that raw prefixes are not repeated. A comparison with the preceding artifact
checked **126 existing types**, preserving every old descriptor, record component, enum order and
sealed shape. Only the five input-limit builder methods, default memory reader and its two provider
overrides were added to existing types; `ContextSummarizationPlan` keeps its two original components.

Final candidate shaded artifact SHA-256:
`00ea4085d319e1250acb475ff177d2421243d7930cb7bb81c812a0e4044cb888`.

Maven ran offline with `-T1`, one Surefire fork, disabled JUnit parallel execution, low process
priority and `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`. One new
value test was corrected to match the existing null-ID exception convention, then rerun with the
focused SDK cases; all other passing CLI results were reused. Strict style, license, Javadocs and
shaded packaging passed. The snapshot and commit exclude the same four unrelated Claude CLI edits,
preserved byte-for-byte. No agents, broad suites, Bytequay tests, live providers, provider CLIs,
native builds or simulators ran. These are contract and recovery checks, not a performance benchmark
or a guarantee of LLM summary fidelity; independent audit/storage retention remains deferred.

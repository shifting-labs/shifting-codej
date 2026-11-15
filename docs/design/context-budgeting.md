# Provider-aware context budgeting

Status: implemented and verified. Extends
[context compaction](context-compaction.md) and [audit retention](audit-retention.md).

## Optional provider contract

The additive `ModelClient.contextBudget(ModelRequest)` default returns empty: input sizing or model
limits are unknown. Existing adapters need no new implementation. A known `ModelContextAssessment`
contains the joint context-window limit, complete input-token count, provider output-token ceiling
and `EXACT` / `ESTIMATED` accuracy. Values are validated; a count exceeding the window is a valid
assessment, not a malformed record. Overflow-safe admission reserves output and an additional margin.

Claude and DeepSeek implement this port through an explicitly installed, borrowed
`ModelContextSizer`. Their `contextSizer(...)` config/facade setters capture the thread-safe service
without calling or closing it. The service receives a private `ModelContextInput` containing provider,
model, serialized active input, requested output cap and deadline. It is local, bounded and cooperative
with interruption/deadlines; no inference, metadata HTTP, tool effects or network counting calls occur.
Its projected content must never be logged, published or retained by observers.

No model-name table, character-to-token heuristic or production tokenizer is installed automatically.
Supply a provider-aware tokenizer/limits service through this interface; unsupported modalities or
unknown limits must return empty. The input count includes provider framing and is independent of
the output cap. Accuracy is declared by that service, not inferred by the engine. An estimate plus a
margin can still underestimate real provider usage; the provider remains authoritative.

## Input projection

Both adapters share typed input/tool records between sizing and actual request construction. Projection
includes effective instructions, tool descriptions/schemas, fresh input and the active native replay:
retained reasoning/signatures, tool calls/results, prior summary and opaque future message fields.
The full canonical audit history is not substituted for compressed active context.

Anthropic attachment projection uses the same bounded encoding of engine-staged private files as
inference. DeepSeek rejects its unsupported attachments. The service can report unknown when it
cannot size a modality. Transport credentials and headers never enter the projection. Default clients
without a sizing service do not serialize or read attachment data for preflight. Runtime failures
from the installed service are normalized without their private diagnostics or causes.
Anthropic's typed host DTOs cross a plain-mapper tree before SDK-model conversion; the SDK mapper
continues to introspect SDK types. Existing signed-block, image, streaming and facade contracts
exercise this boundary alongside the new projected-input comparisons.

## Engine policy and admission

`contextBudget(ContextBudget)` is additive on the engine, both adapter configs and both public API
facades. It is disabled by default. `ContextBudget(safetyMarginTokens, requireKnownSize)` supplies a
nonnegative token margin and explicit handling of unknown assessments. Known estimates are accepted
with their declared accuracy; `requireKnownSize` requires a report, not an exact tokenizer.

Before each inference, the engine projects the complete effective request and asks its adapter for an
assessment. The request's output cap is limited by the configured/remaining turn budget and the
reported provider ceiling. Admission requires:

```text
inputTokens + reservedOutputTokens + safetyMarginTokens <= contextWindowTokens
```

The implementation uses subtraction to avoid overflow. It does not quietly reduce requested output
to make oversized input fit; the provider's output ceiling and the existing remaining turn budget are
the only output-cap reductions. With unknown sizing, the policy either allows the existing request or
fails locally. `ContextAssessed` observations expose only typed counts, accuracy, reservation and the
`FIT`, `OVER_LIMIT`, `UNKNOWN_ALLOWED` or `UNKNOWN_REJECTED` decision.
If output reservation and margin leave no input capacity, the engine rejects immediately rather
than invoking a lossy rewrite that cannot make the request fit.

For a fresh user request in `READY`, measured pressure may invoke the already-installed compactor or
summary policy once, then reproject and reassess the request. The additive default
`ContextCompactionContext.contextAssessment()` is present only for this measured-pressure callback.
A declining policy, unavailable replay, or still-oversized result fails before request intent or HTTP
submission. There is no hidden fallback model or unbounded compaction loop.

Prompt/context contributors are captured once. A pressured rewrite keeps that captured input,
instructions, tools, model/options and deadline; it does not recapture sources or widen authority.
Normal proactive compaction remains available. An upstream policy can opt into pressure-only summaries:

```java
ContextSummarizationPolicy summaries = context -> {
    var history = context.history();
    if (context.contextAssessment().isEmpty() || history.exchanges().isEmpty()
            || !history.complete() && history.summarizedThroughRequestId().isEmpty()) {
        return Optional.empty();
    }
    int retain = Math.min(1, history.exchanges().size() - 1);
    return Optional.of(new ContextSummarizationPlan(retain, 1024));
};

// Supply a real provider-specific tokenizer/limits implementation; no default sizing is guessed.
// ClaudeApi.builder().contextSizer(providerSizer)
//         .contextBudget(new ContextBudget(1024, true)).contextSummarization(summaries) ...
// DeepSeekApi.builder() exposes the same additions.
```

## Summary chunks and tool recovery

Synthetic summary requests use the same preflight policy, complete fixed summary instructions and
output reservation. An oversized candidate is reduced at complete canonical tool-group boundaries
and sized again. The candidate range strictly shrinks; rejected sizing probes consume no model-request
or token-usage budget. Only an admitted summary reserves a request slot and generates a response.
Prior memory and an indivisible group that cannot fit fail locally without truncation or recursive
summarization. Previously committed chunks and retention progress remain recoverable.

Pending tool-result continuations are assessed but never compacted. An oversized batch keeps its
durable tool receipts/results in `TOOLS` for explicit host reconfiguration and resume; handlers are
not reexecuted. Preflight occurs before persisting new `REQUEST` intent, so local sizing rejection
does not turn acceptance into an uncertain outcome. Cancellation retains the writer until the sizing
callback returns. Known request sizing does not replace the independent observed total-turn token
budget or authorize any retry after uncertain delivery.

## Compatibility and plan

No existing constructor, record component, enum member or sealed shape changes. New model-client and
compaction-context methods are defaults. Existing builders gain optional setters; unknown/default
behavior remains unchanged. No checkpoint, provider replay, session-reference or artifact-layout
version changes are needed, and no new dependency, pool or background service is introduced.

- [x] Add typed assessments, private projected input and the optional sizing service/default SPI.
- [x] Add opt-in budget and borrowed sizing configuration through both public API lanes.
- [x] Reuse typed provider request projections including native context, instructions and schemas.
- [x] Preflight fresh, summary and tool-continuation requests before request intent/submission.
- [x] Recheck pressured compaction and reduce summary chunks without splitting tool groups.
- [x] Preserve unknown defaults, output limits, cancellation ownership and durable recovery.
- [x] Add baseline SPI, fake-client, actual-wire projection and fake-HTTP facade tests.
- [x] Complete focused tests, packaged/API compatibility checks, record evidence and commit.

## Validation

The isolated source snapshot based on `52a25e3` passed **375 focused Java cases**, zero failures,
errors or skips:

- **228 CLI cases**: engine (117), retention (5), session store (8), checkpoint migrations (25),
  codec (33), summary encoding (4), model history (6), compaction values (4), memory values (1),
  context-budget values (2), model SPI values (17) and extension observations (6).
- **147 SDK cases**: each public facade (13 each), Anthropic/DeepSeek clients (21/20), shared
  connector contract (9), context-budget projection/facade cases (10), compaction/retention (16),
  each API connector (18 each), replay codec (5) and session-reference compatibility (4).
- **Five Python compatibility-checker controls** passed.

New cases cover disabled defaults, unknown allowed/rejected, null/throwing sizing services, exact /
estimated reports, overflow-safe reservations, provider output caps, unchanged request settings,
fresh pressure success/decline/insufficient reduction, adaptive summary chunks, an indivisible
oversized group, impossible output reservations, tool-result recovery without reexecution, and
writer ownership until an uncooperative callback stops. Both adapters' projected inputs compare
equal to their actual wire input fields, including native private blocks and tool schemas. Sizing
adds no HTTP requests. The fake HTTP facade cases compact only on pressure and size active replay
after restart rather than resending raw audit history.

The final shaded artifact passed the published `0.164.0-1` compatibility check and all packaged
consumers. `ContextBudgetConsumer` uses only the JDK and shaded jar and performs four conversation
requests plus one pressured summary per provider. Its four sizing passes include the original
oversized input, isolated summary, reduced fresh request and resumed request. Character counts are
explicitly simulated token counts in this fixture, not a production estimator.

A separate comparison with the preceding artifact checked **131 existing types**: every old
descriptor, record component, enum order and sealed shape is unchanged. Existing types gained
five budget setters, four borrowed-sizer setters, the model-client default and two adapter overrides,
and the compaction-context default. No new mandatory implementation method or persistence migration
was introduced.

Final candidate shaded artifact SHA-256:
`9343e02e388357a1be9b6b7e72aab3a6a0082c18225f7521745f0d2e4b21b8cd`.

The final snapshot was verified against the current main revision and excludes the four unrelated
Claude CLI edits, preserved byte-for-byte. Maven ran offline with `-T1`, one Surefire fork, disabled
JUnit parallel execution, low process priority and
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`. Passing engine/DeepSeek
results were reused while correcting the typed Anthropic SDK conversion boundary; the affected
Claude and projection contracts passed afterward. The final reservation guard was tested and the
packaged compatibility checks rerun on that artifact. Strict style, license, Javadocs and shading
passed. No agents, broad suites, Bytequay tests, live providers, provider CLIs, native builds or
simulators ran. Production tokenizer accuracy, live model limits and performance benchmarks remain
outside this check; sizing stays explicitly unknown unless an appropriate service is installed.

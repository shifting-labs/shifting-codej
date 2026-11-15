# Provider-aware context budgeting

Status: implemented and verified. Extends
[context compaction](context-compaction.md) and [audit retention](audit-retention.md).

## Optional provider contract

The additive `ModelClient.contextBudget(ModelRequest)` default returns empty: input sizing or model
limits are unknown. Existing adapters need no new implementation. A known `ModelContextAssessment`
contains the joint context-window limit, complete input-token count, provider output-token ceiling
and `EXACT` / `ESTIMATED` accuracy. Values are validated; a count exceeding the window is a valid
assessment, not a malformed record. Overflow-safe admission reserves output and an additional margin.

Claude and DeepSeek implement this port through a local `ModelContextSizer`. Enabling
`contextBudget(...)` now selects built-in text sizing when no custom service is installed.
Their `contextSizer(...)` config/facade setters capture a borrowed thread-safe service
without calling or closing it. The service receives a private `ModelContextInput` containing provider,
model, serialized active input, requested output cap and deadline. It is local, bounded and cooperative
with interruption/deadlines; no inference, metadata HTTP, tool effects or network counting calls occur.
Its projected content must never be logged, published or retained by observers.

Built-ins use a reviewed, finite model catalog and deliberately conservative estimates; they are not
production tokenizers. An explicitly installed service always takes precedence, including an empty
result. Hosts can therefore retain unknown behavior with `contextSizer(input -> Optional.empty())`,
or install their own model-limit/tokenizer service. Unknown limits and unsupported modalities return
empty. The input count includes provider framing and is independent of the output cap. An estimate
plus a margin can still underestimate real provider usage; the provider remains authoritative.

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

## Built-in local sizing

`BuiltinContextSizer` is an internal implementation of the existing `ModelContextSizer` SPI. Both
immutable provider configs select their own shared stateless implementation only when budgeting is
enabled and no custom service was provided. Nothing changes for callers without `contextBudget`;
no new public method, dependency, remote counting call, worker or cache is required.

The initial explicit catalog was reviewed on **2026-11-15**:

| Claude IDs | Joint context / maximum output tokens |
| --- | --- |
| `claude-fable-5-2`, `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5` | 1,000,000 / 128,000 |
| `claude-fable-5-1`, `claude-fable-5`, `claude-opus-5`, `claude-sonnet-5` | 1,000,000 / 128,000 |
| `claude-opus-4-8`, `claude-opus-4-7`, `claude-opus-4-6`, `claude-sonnet-4-6` | 1,000,000 / 128,000 |
| `claude-opus-4-5`, `claude-opus-4-5-20251101` | 200,000 / 64,000 |
| `claude-sonnet-4-5`, `claude-sonnet-4-5-20250929` | 200,000 / 64,000 |
| `claude-haiku-4-5`, `claude-haiku-4-5-20251001` | 200,000 / 64,000 |

Sources: [Claude model limits](https://platform.claude.com/docs/en/models/overview),
[model IDs and snapshots](https://platform.claude.com/docs/en/about-claude/models/model-ids-and-versions).
These are standard limits; optional larger beta windows are not assumed. A catalog entry describes
sizing, not availability, access rights or new adapter capabilities.

DeepSeek IDs `deepseek-flash`, `deepseek-v4-pro`, `deepseek-v4-flash` and
`deepseek-v4-flash-vision-exp` use a 1,048,576-token joint window and 393,216-token output ceiling.
The last two are documented compatibility aliases. Retired `deepseek-chat` and `deepseek-reasoner`
remain unknown. Sources: [machine-readable limits](https://api-docs.deepseek.com/api/list-models/),
[accepted aliases](https://api-docs.deepseek.com/quick_start/pricing/),
[retirement notice](https://api-docs.deepseek.com/news/news260424/).
Future IDs, arbitrary dated suffixes and proxy-specific names never inherit limits by prefix.
Adding a catalog entry requires a source and baseline case; a custom sizer can supply other limits
without an engine change. The bundled catalog does not fetch updates or promise live availability.

All built-in results are `ESTIMATED`. The estimate is one token per UTF-8 byte of the **entire
serialized active input**, plus 1,024 framing tokens, 64 per message, 256 per tool definition, and
32 per Claude content block. Counting keys, escaping, signatures and retained thinking deliberately
errs toward earlier compaction, especially for English text. These framing allowances are engineering
heuristics, not provider-specified token costs or a mathematically guaranteed upper bound. The existing
host safety margin is additional. No prompt is truncated. The UTF-8 scan uses long arithmetic, avoids
an input-sized byte-array allocation, and checks interruption/deadline periodically.

Strict immutable records describe the supported projections. Claude accepts text, thinking,
redacted thinking, client tool calls with opaque JSON arguments, and string tool results; tool schemas
remain opaque JSON objects. DeepSeek accepts text/system/tool messages, function calls, retained
reasoning and function definitions. Unknown fields, block types, image/document inputs, malformed
projections, scalar coercions and unsupported tool-result content return unknown instead of pretending
the encoded bytes measure a non-text modality. Parser diagnostics never escape. Existing custom
services still receive the complete native projection, including images they know how to count.

Claude offers a [remote counting endpoint](https://platform.claude.com/docs/en/build-with-claude/token-counting),
while DeepSeek documents [offline tokenizer assets](https://api-docs.deepseek.com/quick_start/token_usage/).
Neither is silently invoked or downloaded. A later exact-counting stage would need a separately
explicit remote-operation policy or a reviewed local tokenizer/artifact implementation.

```java
// Built-in estimates; unknown models/modalities fail locally with this strict policy.
// ClaudeApi.builder().contextBudget(new ContextBudget(1024, true)) ...
// DeepSeekApi.builder() exposes the same policy.
// .contextSizer(customSizer) overrides built-ins; returning empty does not fall back.
```

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

// Built-in estimates or an explicit provider tokenizer/limits override.
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
compaction-context methods are defaults. Existing builders retain their descriptors. Budgeting remains disabled by default; enabling it now
uses built-in estimates for the finite catalog when no custom sizer was installed. Unknown inputs
still follow the existing explicit policy. No checkpoint, provider replay, session-reference or artifact-layout
version changes are needed, and no new dependency, pool or background service is introduced.

- [x] Add typed assessments, private projected input and the optional sizing service/default SPI.
- [x] Add opt-in budget and borrowed sizing configuration through both public API lanes.
- [x] Reuse typed provider request projections including native context, instructions and schemas.
- [x] Preflight fresh, summary and tool-continuation requests before request intent/submission.
- [x] Recheck pressured compaction and reduce summary chunks without splitting tool groups.
- [x] Preserve unknown defaults, output limits, cancellation ownership and durable recovery.
- [x] Add baseline SPI, fake-client, actual-wire projection and fake-HTTP facade tests.
- [x] Complete focused tests, packaged/API compatibility checks, record evidence and commit.

## Built-in sizing plan

- [x] Verify official provider limits and document catalog scope and estimate accuracy.
- [x] Implement a bounded local sizer through the existing SPI with typed projection records.
- [x] Wire provider defaults only for opt-in budgeting; keep custom unknown results authoritative.
- [x] Cover Unicode, private native blocks, tools, unsupported input, deadlines and exact model matching.
- [x] Cover both public facades, provider output limits, pressured summaries and durable resume.
- [x] Run focused isolated tests and packaged/API compatibility checks, record evidence, commit.

## Previous budget-stage validation

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
simulators ran. Production tokenizer accuracy, live model availability and performance benchmarks remain outside this
check. The following built-in stage adds local estimates for its explicitly documented catalog.


## Built-in sizing validation

An isolated snapshot based on `f3e77d3` passed **183 focused Java cases**, with zero failures,
errors or skips: built-in sizing (48), context-budget projection/facade behavior (16), public
Claude/DeepSeek facades (13 each), Anthropic/DeepSeek model clients (21/20), each API connector
(18 each), and context compaction (16). Passing unaffected Claude contracts were reused while
correcting the fake metadata catalog and readiness/inference counters; the affected DeepSeek and
sizing cases passed afterward. The strict unit reader also confirms that a future field in signed
Claude native content produces unknown sizing without rewriting that native content.

The build passed strict style, license, Javadocs and shaded packaging checks. The published
`0.164.0-1` compatibility suite and packaged consumers passed. The context-budget consumer now
runs both public facades with built-ins and no custom service, alongside its existing custom-sizing,
pressured-compaction and resume checks. Direct sizing performs no HTTP; facade session startup
retains its existing readiness request. Output remains capped by both provider and existing engine
turn limits. A separate comparison against the preceding engine artifact verified **138 existing
types** with identical descriptors, record components, enum order and sealed shapes; no public API
was added or changed.

Candidate shaded artifact SHA-256:
`3ae063d0fae42a8bab7157ba0556d07e96876bbcf7f4a3a741f14a7942c4e77c`.

All checks used one active CPU, one test fork, disabled JUnit parallel execution, offline Maven,
low process priority, a 512 MB heap and SerialGC. The four unrelated Claude CLI edits were excluded
from the snapshot and preserved byte-for-byte. No agents, broad test suites, live provider calls,
provider CLIs, Xcode builds or simulators ran. Exact tokenizer accuracy, live model availability and
performance benchmarks were not tested; estimates can cause earlier compaction and are not a
provider acceptance guarantee.

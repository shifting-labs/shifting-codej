# Bounded tool scheduling

Status: shared-budget prerequisite implemented and verified; batch overlap remains planned. Follows [policy extensions](policy-extensions.md)
and the [DeepSeek Harness study](deepseek-harness-study.md).

## Evaluation and staged decision

Keep existing batches serial. Concurrent driver sessions already execute independently, so their
aggregate tool concurrency needs a shared host limit before overlapping a batch. Introduce an
explicit borrowed `ToolExecutionBudget`, which a host may install on multiple engine/provider
builders. Avoid a static global registry, implicit process-wide defaults or another executor pool.
Unconfigured engines retain their current limits and behavior.

The built-in bounded implementation uses fair semaphore admission on the existing turn worker.
Admission checks cancellation and deadlines before waiting and after acquiring capacity. Interruption
aborts a wait; cancellation without interruption is rechecked at bounded intervals. No background
thread, scheduler, subprocess, inference or busy loop is created by the budget.

Acquire only after validation, permissions, policies and approval, before durable tool intent or
handler invocation. Waiting for capacity never marks a receipt started. Cancellation releases a
reservation that has not entered a handler; once invoked, outcome and verified shutdown determine
release. Completion/cancellation alone cannot release capacity. Failed result persistence or resource
acknowledgement retains ownership until cleanup confirms shutdown. If startup fails before returning
a handle, retain its reservation until session-owned tool resources close successfully.

Budget implementations are trusted host services. Failed acquisition must leave no capacity owned
by the caller. A returned lease is uniquely owned, with nonblocking, idempotent release. Drivers
borrow the budget and never close it. Limits apply only to drivers configured with the same instance,
and count invocations, not internal threads, subprocess descendants, CPU or remote API requests.
This is an in-process limit; sharing across JVMs requires a separately implemented host service.

## Batch overlap requirements

A later opt-in scheduler needs all of these before changing runtime dispatch:

1. Explicit eligibility from typed tool implementations; legacy wrappers, built-ins, shell and
   unknown/custom effect scopes remain exclusive by default. A read declaration alone proves
   neither implementation thread safety nor the absence of hidden shared state.
2. A per-turn window bounded by the shared budget. Do not queue an unbounded model batch or assume
   separate tools/families own independent resources.
3. Serial validation, policy and human approval, durable admission before effects, exclusive
   barriers, and provider-order result batches regardless of completion order.
4. Atomic checkpoint read/modify/save for asynchronous prepared-resource callbacks. The repository's
   individually synchronized methods do not make separate state()/save() operations atomic.
5. Cancel every active execution, drain callbacks and verify local work stopped before releasing
   leases, writers, resource scopes or session capacity. Persist observed results once, including
   success that races cancellation; never re-execute uncertain receipts after restart.
6. Keep observer callbacks serial and bounded. Synchronous execute methods cannot overlap without
   more workers; prefer existing asynchronous execution handles before adding an executor.

This stage implements the shared admission prerequisite and its consumer wiring. Batch overlap,
eligibility declarations and asynchronous checkpoint serialization belong to the next implementation
stage, rather than unused new SPI methods in this commit.

## Checkbox plan

- [x] Evaluate current dispatch, async handles, checkpoint updates and cancellation fences.
- [x] Add a typed shared budget/lease contract and bounded implementation without new workers.
- [x] Integrate reservation ownership with validation, approval, dispatch and resource cleanup.
- [x] Wire both API provider configs and public builders with additive opt-in methods.
- [x] Add baseline budget, dispatcher, cross-driver and upstream fake-HTTP tests.
- [x] Run focused tests and packaged/API compatibility checks with bounded JVMs and one fork.
- [x] Update plans, preserve unrelated edits and commit the completed shared-budget stage.
- [ ] Implement opt-in batch overlap only after the eligibility, checkpoint and draining contracts above.

## Usage and validation

Both public API builders and their connector configs accept `toolExecutionBudget(budget)`, as does
`ApiEngineConfig.Builder`. The host can compose an aggregate limit without changing either connector:

```java
ToolExecutionBudget shared = ToolExecutionBudget.bounded(2);
ClaudeApi.Builder claude = ClaudeApi.builder().toolExecutionBudget(shared);
DeepSeekApi.Builder deepseek = DeepSeekApi.builder().toolExecutionBudget(shared);
```

These are builders; model, credentials and other settings still use the existing methods. An unset
budget preserves current behavior. A configured budget is never acquired during configuration or
session opening, never bypasses permissions/approval, and is never closed by one of its borrowers.
The [packaged consumer](../../scripts/compatibility/ToolSpiConsumer.java) demonstrates installation,
while both facade tests exercise custom host-service implementations through fake HTTP.

Validation passed **240 focused cases**, with zero failures, errors or skips:

- 177 CLI cases: `TestToolExecutionBudget` (6), `TestToolDispatcher` (17),
  `TestApiAgentDriver` (62), `TestApiToolProviders` (21), `TestApiSessionStore` (6),
  `TestApiCheckpointCodec` (33), `TestApiCheckpointMigrations` (25),
  `TestEngineExtensionSpi` (3), `TestPromptSpiValues` (4).
- 63 SDK cases: `TestClaudeApi` and `TestDeepSeekApi` (12 each), `TestApiConnector` (9),
  `TestClaudeApiConnector` and `TestDeepSeekApiConnector` (13 each),
  `TestSessionReferenceCompatibility` (4).
- New coverage exercises aggregate admission across independent drivers, cancellation/deadlines
  before intent, noncooperative callbacks retaining capacity, returned/null/throwing/interrupted
  acquisitions, idempotent release, extreme deadlines, post-acquisition failure, rejection ordering,
  uncertain startup, failed cleanup, unverified stop, failed result persistence and acknowledgement.
- Checkstyle, license checks, strict Javadocs and shaded `-Ppublish` packaging passed.
- The published `0.164.0-1` checker passed unchanged consumer bytecode, public/driver signatures and
  shapes, checksum/shading checks, fake-HTTP API fixtures, contained coding tools and the configured
  tool/prompt/extension/budget consumer with only the JDK and candidate artifact.
- A separate comparison against the preceding `07f74cd` artifact verified all 112 existing engine,
  connector, model/tool/prompt/extension/resource and facade types. Only five additive builder methods
  were allowed; descriptors, record components, enum order and sealed shapes remained unchanged.

Verification used an isolated snapshot excluding the four pre-existing unrelated Claude CLI edits;
their SHA-256 values were checked unchanged. Maven ran offline with `-T1`, one Surefire fork, disabled
JUnit parallel execution, low process priority and
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`.
The tested shaded candidate SHA-256 is
`5b1e57a068d96b138089d10dd8967d5a9f7d03f536592492af106cc2f5b2200d`.
No agents, live inference, provider CLIs, broad/Bytequay suites, Xcode builds or simulators ran.
This verifies admission and ownership behavior, not a throughput benchmark or parallel batch scheduler.

# Bounded tool scheduling

Status: shared-budget admission and opt-in asynchronous batch overlap implemented and verified. Follows [policy extensions](policy-extensions.md)
and the [DeepSeek Harness study](deepseek-harness-study.md).

## Evaluation and staged decision

The shared-budget prerequisite kept batches serial. Concurrent driver sessions execute independently, so their
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

The opt-in scheduler follows these requirements:

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

The prerequisite introduced shared admission and consumer wiring. The batch-overlap stage below
adds eligibility, bounded scheduling and atomic asynchronous checkpoint updates.

## Checkbox plan

- [x] Evaluate current dispatch, async handles, checkpoint updates and cancellation fences.
- [x] Add a typed shared budget/lease contract and bounded implementation without new workers.
- [x] Integrate reservation ownership with validation, approval, dispatch and resource cleanup.
- [x] Wire both API provider configs and public builders with additive opt-in methods.
- [x] Add baseline budget, dispatcher, cross-driver and upstream fake-HTTP tests.
- [x] Run focused tests and packaged/API compatibility checks with bounded JVMs and one fork.
- [x] Update plans, preserve unrelated edits and commit the completed shared-budget stage.
- [x] Implement opt-in batch overlap with the eligibility, checkpoint and draining contracts above.

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
This prerequisite evidence covers admission/ownership behavior. Batch-overlap evidence follows below.

## Opt-in batch overlap

The next stage adds `maximumParallelTools(1..64)` on the engine, API configs and public facades.
One remains the default; a value greater than one requires an explicit shared budget. Tool authors
separately opt in through the additive default `AgentTool.parallelSafe()`. Captured registration
metadata, validated requirements, session permissions, policy and approval all remain authoritative.
Only pure operations and scoped workspace reads qualify in this stage. Writes, process/network/host
invocation, custom capabilities, legacy wrappers and built-ins are exclusive barriers even if a
caller marks them parallel-safe. Read declarations without implementation opt-in remain exclusive.

The coordinator starts asynchronous handles on the existing turn worker and keeps at most the
configured window active. Inputs are prepared lazily in provider order; no batch-sized execution
queue or executor pool is created. Calls requiring a barrier drain previous work, execute alone,
and settle before later calls start. These barriers apply within the batch; the shared host budget bounds
aggregate capacity across driver sessions. Synchronous execute methods naturally remain synchronous.

`ToolExecutionBudget.tryAcquire` is a nonblocking additive default. The built-in budget implements
it; existing host implementations default to empty and retain serial admission. A coordinator
holding leases never calls blocking acquire: if capacity is unavailable it settles active work
before retrying. This avoids self-deadlock when the host budget is smaller than the batch window.

Policies, human approvals, result encoding, durable outcome commits, acknowledgements and observers
run serially on the coordinator. Ready executions may settle in completion order, but the model's
result batch is assembled in original provider order from the saved receipts. Frontend events are
serialized, including progress from asynchronous resource callbacks. Compound prepared-resource,
started and result transitions hold the repository monitor around state/read-modify-save, avoiding
lost journal updates without changing checkpoint format or public record shapes.

Turn cancellation requests every returned active handle immediately, including while approval is
pending; late-returning handles receive the same request once. Cancellation or another batch failure
stops admission and drains the admitted prefix. Observed success is encoded/persisted before releasing
its lease; unknown outcomes, failed persistence/acknowledgement and unverified termination retain
claims for cleanup/reconciliation. Unstarted queued calls remain unstarted. Restart recovery keeps
the existing saved-result/synthetic-interruption behavior and never re-executes old receipts.

### Batch-overlap checklist

- [x] Add default exclusive eligibility and opt-in bounded builder configuration.
- [x] Add nonblocking shared-budget admission with a serial fallback for existing budget implementations.
- [x] Implement a lazy bounded scheduler, exclusive barriers, provider-order result assembly and serial observers.
- [x] Make asynchronous journal updates atomic and serialize progress delivery.
- [x] Cancel all active handles and drain observed outcomes while retaining unsafe claims.
- [x] Finish focused scheduler/engine/fake-HTTP regression verification and packaged/API compatibility checks.
- [x] Review, preserve unrelated edits, update validation evidence and commit this stage.

### Enabling and testing batch overlap

```java
ToolExecutionBudget shared = ToolExecutionBudget.bounded(4);
ClaudeApi.Builder claude = ClaudeApi.builder()
        .toolExecutionBudget(shared).maximumParallelTools(2);
DeepSeekApi.Builder deepseek = DeepSeekApi.builder()
        .toolExecutionBudget(shared).maximumParallelTools(2);
```

Configured typed tools also override `parallelSafe()` to return true. Existing implementations inherit
false. The [controlled async lookup fixture](../../shifting-codej/src/test/java/io/github/shiftinglabs/codej/sdk/testing/ParallelLookupTools.java)
shows installation through both facade tests; it deliberately withholds one fake result to verify
reverse completion. The standalone [artifact consumer](../../scripts/compatibility/ToolSpiConsumer.java)
uses a two-call batch with explicit eligibility and a shared budget, while validating typed result
order, history and observer delivery from the shaded artifact.

Validation passed **255 focused cases**, with zero failures, errors or skips:

- 190 CLI cases: `TestParallelToolDispatcher` (9), `TestApiToolProviders` (24),
  `TestToolExecutionBudget` (7), `TestToolDispatcher` (17), `TestApiAgentDriver` (62),
  `TestApiCheckpointCodec` (33), `TestApiSessionStore` (6), `TestApiCheckpointMigrations` (25),
  `TestEngineExtensionSpi` (3), `TestPromptSpiValues` (4).
- 65 SDK cases: `TestClaudeApi` and `TestDeepSeekApi` (13 each), `TestApiConnector` (9),
  `TestClaudeApiConnector` and `TestDeepSeekApiConnector` (13 each),
  `TestSessionReferenceCompatibility` (4).
- New coverage verifies genuine overlapping handles and reverse settlement, per-batch windows,
  smaller shared budgets, old-budget serial fallback, all exclusive barriers, policy/approval/static
  denials, prefix draining on request-limit/stop/persistence/acknowledgement/startup failure,
  cancellation during approval, bounded stop-proof interruption, retained leases and writer claims,
  concurrent durable resource callbacks, serialized progress, ordered fake-HTTP results and restart
  reconciliation without re-execution of either completed or unstarted receipts.
- Checkstyle, license checks, strict Javadocs and shaded `-Ppublish` packaging passed.
- The published `0.164.0-1` packaged checker passed unchanged baseline-compiled consumer bytecode,
  legacy public/driver descriptors and shapes, checksum/shading checks, API fake-HTTP/coding fixtures
  and the updated configured SPI consumer.
- Comparison against the preceding `6fcf432` artifact verified all 116 existing engine, SPI, resource
  and facade types. Five builder methods, two default SPI methods and the package-private budget's
  nonblocking override are additive; existing descriptors, record components, enum order and sealed
  shapes match. No existing implementation gains a mandatory method.

Verification used the same isolated snapshot, unrelated-file hash checks and bounded offline Maven
settings described above. No agents, new production worker pools, live inference, provider CLIs,
broad/Bytequay suites, Xcode builds or simulators were started. The tests use deterministic futures
and at most two controlled resource-callback threads; this is not a throughput benchmark.
The final shaded candidate SHA-256 is
`c9fe4fcb4089fee9cd0718757bfd6b9c87471d8eee72559c9f545143d0de06e7`.

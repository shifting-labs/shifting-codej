# Scoped policy extensions and observers

Status: implemented and verified. Follows [prompt contributors](prompt-contributors.md).

## Contracts

`EngineExtensionProvider<C>` opens a fresh `EngineExtension` owned by each underlying driver session from explicit immutable
configuration. The extension implements two separate ports: `ToolPolicy` and `EngineObserver`, with
neutral defaults, plus bounded, retryable cleanup. This allows one scoped integration to supply a
policy, an observer, or both without duplicating lifecycle machinery. Provider IDs and registration
order are captured at configuration time; duplicates are rejected before opening resources.

`ToolPolicy` receives typed, prepared tool requirements, tool name, turn/invocation identity, deadline
and cancellation state. It returns `CONTINUE` or `DENY`. CONTINUE grants no authority. All policies
must continue, in registration order, after validation and static permission checks and before human
approval or durable tool admission. A null or throwing policy fails closed for that invocation.
Arguments, output, prompts, credentials and execution handles are deliberately absent from this SPI.
Existing backend containment, mandatory approvals and durable launch gates remain authoritative.
Typed providers expose their declared capability/path requirements. Legacy wrappers, including the
current built-in compatibility path, declare generic `HOST_INVOKE`; policies can restrict their tool
names but cannot infer file targets or shell commands. Argument-level policy requires a future typed
intent contract; this stage neither guesses from JSON nor changes legacy binding/execution behavior.

`EngineObserver` receives immutable metadata-only `EngineObservation` values: turn start, settled
model response, tool decision, settled tool result and terminal turn outcome. The observation
interface is open so adding a notification type does not extend an existing sealed hierarchy.
Callbacks run serially on the existing turn worker, in registration order; no queue or new pool is
created. Callback failures disable that observer for the remaining session, without disabling
its policy or changing execution outcomes. The engine redacts known credentials in string metadata
and substitutes its generated request ID for provider usage identifiers. Observations are best effort,
never an audit ledger.

Factories and callbacks are trusted Java code, must be bounded and cooperate with interruption.
The engine retains writer/capacity claims until callbacks return and owned resources close. An open
failure unwinds acquired resources in reverse order; a close failure retains ownership for retry.
Observers must not block or perform tool effects. Cancellation interrupts callbacks but never treats
interruption alone as proof that local work has stopped.

## Compatibility and recovery

New opt-in builder methods reach `ApiEngineConfig`, both API provider configs and both public API
facades. Empty rosters preserve current behavior. No existing public method, record component, enum
or sealed hierarchy changes. No checkpoint migration is needed: policies run only for fresh dispatch;
pending receipts keep the existing interrupted-batch reconciliation behavior. Observations are not
replayed after restart, and a terminal notification describes the already selected execution outcome
before session cleanup. Cleanup failure may still change public completion to FAILED. The deadline
remains active through notifications.

This stage supplies restrictive tool policies and metadata observations. Model routing, mutable
request rewriting, approval grants, result transformation, plugin hot reload, persisted telemetry and
asynchronous observer delivery remain separate work.

## Checkbox plan

- [x] Add validated lifecycle bindings, typed policy context and metadata observation contracts.
- [x] Install scoped extensions and preserve acquisition, cancellation and cleanup ownership.
- [x] Evaluate policies before approval/admission; report durable outcomes without exposing content.
- [x] Forward configuration through Claude API and DeepSeek API facades/configs.
- [x] Add baseline SPI, engine, failure/recovery and upstream fake-HTTP examples.
- [x] Run focused tests and compatibility checks with one fork and bounded JVM resources.
- [x] Review, update this plan, preserve unrelated edits and commit.

## Consumer examples and validation

Both `ClaudeApi.Builder` and `DeepSeekApi.Builder` accept `extension(provider, configuration)`.
The engine/config builders also accept `extension(EngineExtensionBinding)` for composition. Build
captures immutable ordered rosters, with a maximum of 64 extensions and no eager factory opens.
Factories must return independent session instances; their state and observer-disable flags are not
checkpointed or shared between sessions. SDK session suspension may close and reopen driver scopes.

[`ConfiguredExtensions`](../../shifting-codej/src/test/java/io/github/shiftinglabs/codej/sdk/testing/ConfiguredExtensions.java)
is an external-package consumer implementing a configured restrictive policy plus a metrics observer.
The Claude/DeepSeek facade tests install it, deny without prompting/executing, restart with a continuing
policy, and verify normal human approval, execution and independent scope cleanup. The standalone
[`ToolSpiConsumer`](../../scripts/compatibility/ToolSpiConsumer.java) exercises both ports from the
shaded artifact with only the JDK on its classpath.

Validation passed **221 focused cases**, with zero failures, errors or skips:

- 162 CLI cases: `TestEngineExtensionSpi` (3), `TestPromptSpiValues` (4),
  `TestApiAgentDriver` (59), `TestApiCheckpointCodec` (33), `TestApiSessionStore` (6),
  `TestToolDispatcher` (12), `TestApiToolProviders` (20), `TestApiCheckpointMigrations` (25).
- 59 SDK cases: `TestClaudeApi` and `TestDeepSeekApi` (11 each), `TestApiConnector` (9),
  `TestClaudeApiConnector` and `TestDeepSeekApiConnector` (12 each),
  `TestSessionReferenceCompatibility` (4).
- Coverage includes validation/permission ordering, denial/null/throwing decisions, approval
  preservation, metadata filtering, observer isolation, per-session state, interrupted recovery,
  reverse cleanup, failed-close ownership, reentrant initialization, noncooperative cancellation,
  terminal callback errors and deadline interruption through the final notification.
- Checkstyle, license checks, strict Javadocs and `-Ppublish` shaded packaging passed. Documentation
  fixes were packaged with tests skipped after the focused cases passed; no broad suite was run.
- The published `0.164.0-1` checker passed unchanged baseline-compiled bytecode, signatures/shapes,
  shading/checksum resources and focused packaged API/coding-tool/SPI consumer fixtures.
- A separate comparison against the preceding `aa72237` artifact verified all 97 existing engine,
  connector, model/tool/prompt/resource and facade types. Only eight additive builder overloads were
  allowed; existing descriptors, record components, enum order and sealed shapes matched.

Verification used an isolated source snapshot excluding the four unrelated Claude CLI edits, whose
SHA-256 values remained unchanged. Maven ran offline with `-T1`, one test fork, disabled JUnit
parallelism, low process priority and
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`.
The tested shaded candidate SHA-256 is
`5df7cbca52adb3bb5bd24d7cfa6c9012a38079c2d47274fe5f3c8732a1eab45e`.
No agents, live inference, provider CLIs, broad/Bytequay suites, Xcode builds or simulators ran.

# DeepSeek Harness study and engine separation

Status: initial engine separation and typed state implemented and verified. Reviewed upstream `master` on 2026-11-14; this is a
source-level study, not a runtime integration, performance benchmark or security audit.

## Decision and compatibility boundary

Keep Codej's Java API and complete-runtime connector boundary. Continue owning the Java
harness for direct API providers. Learn from DeepSeek Harness (DSH) before introducing further
runtime features. A future DSH integration belongs behind `AgentConnector` / `ConnectorDriver`,
not `ModelClient`: DSH owns an agent loop, whereas a model client owns one inference call.
Authentication remains independent of runtime choice; both CLI and direct API lanes may use keys.

Keep existing public signatures, default behavior, records, enums and sealed hierarchies unchanged.
Keep version-1 checkpoint names, fields, private replay and pending-tool recovery readable.
The initial separation is package-private inside `io.github.shiftinglabs.codej.cli.api`;
`ApiAgentDriver` remains the public facade. It requires no downstream Bytequay migration.

## Findings

DSH uses Cordis services, declared dependencies, typed events and scope-owned reversible
registrations. Its composition can replace model adapters, the session service, tools, prompt
assembly and the agent loop. This gives it more internal extension points than our current engine.
Its Agent contract and factory are separate from the concrete loop implementation.

Sources: [architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md),
[Cordis](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cordis-primer.md),
[Agent](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent/README.md),
[loop](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent-loop/README.md).

Its append-only session events produce conversation projections; persistence is a separate service.
Adapter-private replay remains adapter-owned. Prompt sections and tool definitions are composed
for each step. These are useful boundaries for typed state, recovery and future context policies.

Sources: [sessions](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/session/README.md),
[prompts](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/system-prompt/README.md),
[LLM/replay](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/llm-streaming.md).

The tool pipeline separates extensible decisions from monotonic denial guards, execution wrappers,
result transformation and observation. Its scheduler overlaps explicitly eligible calls with a
bounded pool, exclusive barriers, ordered results and draining cancellation. Codej should retain
its backend containment, durable admission and stop verification when adopting these ideas.

Sources: [tools](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/tools/README.md),
[scheduler](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/tools/src/batch.ts).

DSH has native DeepSeek authentication adapters and a pi-ai multi-provider adapter. The latter
implements configurable Anthropic Messages, OpenAI Completions/Responses and Google Generative AI
protocols. This supports multiple API providers without turning native CLI runtimes into model clients.

Sources: [LLM family](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/llm/README.md),
[pi-ai adapter](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/llm/llm-pi-ai/README.md),
[protocol table](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/llm/llm-pi-ai/src/provider.ts).

Its Codex backend starts an app-server and its Claude Code backend invokes the Agent SDK's real
CLI process. Both are one-shot external delegations: no native resume, continuation, progress
stream or interactive human approval. They cannot replace our richer native session adapters unchanged.

Sources: [Codex](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/subagent/subagent-codex/README.md),
[Claude Code](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/subagent/subagent-claude-code/README.md).

Direct runtime reuse would require a Node sidecar and a Codej protocol adapter. The SDK exposes
stdio JSON-RPC, but currently has no session-close or cancel methods and no protocol-version
negotiation. Process cancellation needs isolation so cancelling one conversation cannot stop
another. Upstream is developer preview and explicitly anticipates breaking changes. These are
integration requirements, not reasons to weaken Codej's existing contracts.

Sources: [SDK protocol](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/sdk/protocol/README.md),
[status/license](https://github.com/deepseek-ai/deepseek-harness/blob/master/README.md),
[safety limitations](https://github.com/deepseek-ai/deepseek-harness/blob/master/SAFETY.md).

## Initial implementation

Separate construction/ownership (`ApiEngine`), session resources (`ApiSession`), and turn execution
(`ApiTurn`). The facade delegates through the existing complete-runtime contract. Inject an internal
session-repository factory and prompt assembler; use explicit typed dependencies rather than a
global mutable service locator. The assembler supplies instruction text only; model selection and
the tool catalog remain engine-owned. The default implementation preserves instruction ordering.

Represent checkpoint metadata, phases, request setup, tool receipts and execution journal entries
as validated immutable records. State transitions return new snapshots; JSON trees never enter
session or turn logic. One strict checkpoint codec owns wire translation, rejects scalar coercion,
duplicate keys, malformed records and incompatible metadata, and preserves the existing version-1
wire layout. Optional legacy tool manifests remain accepted only for matching legacy registrations.
Provider replay stays opaque and private; this step does not migrate conversations across providers.

The codec binds explicit wire records in one parsing pass. Its Optional adapter distinguishes
missing legacy properties from explicit null, and textual/integer/boolean coercion is disabled.
Neither a malformed manifest nor a changed schema/version can silently become a matching tool.
Immutable snapshots avoid JSON deep copies on each runtime read. The file backend still performs
synchronous full-checkpoint writes; append-only storage and performance comparisons are separate work.

Keep the current durability barriers, atomic replacement, single writer, bounded workers, approvals,
recovery behavior and borrowed-resource ownership. Injected repository implementations must honor
the same commit/failure contract. No background persistence, new pools, retry policy, tool
parallelism, hot reload, DSH dependency or new public SPI is introduced in this step.

## Checkbox plan

- [x] Study DSH composition, sessions, prompt assembly, tool execution, adapters and SDK limitations.
- [x] Compare direct runtime reuse with selective Java adoption; retain the existing public boundary.
- [x] Separate public facade, engine ownership, sessions and turns.
- [x] Introduce internal repository and prompt-assembly contracts with default implementations.
- [x] Replace runtime JSON state with validated immutable checkpoints, setup, receipts and journal records.
- [x] Isolate strict JSON codecs and preserve version-1 files and legacy pending batches.
- [x] Add focused typed-state, malformed-file, recovery and component-boundary coverage.
- [x] Run affected engine/store/dispatcher/provider tests with one fork and bounded JVM resources.
- [x] Verify unchanged public signatures and preserve unrelated work; commit the completed step.
- [ ] Add provider-neutral conversation projections and explicit versioned migrations separately.
- [ ] Add scoped prompt/context contributors, policy extensions and observers when their callers exist.
- [ ] Evaluate bounded parallel tools under a host-wide budget, with cancellation/ordering contracts.
- [ ] Evaluate an opt-in DSH connector with pinned runtime, per-session cancellation, approval/event
      mapping and focused connector contract tests before any default-route change.

## Validation

The final isolated source snapshot passed **153 focused cases**, with zero failures, errors or skips:

- 102 CLI-module cases: `TestApiAgentDriver` (33), `TestApiCheckpointCodec` (31),
  `TestApiSessionStore` (6), `TestToolDispatcher` (12), and `TestApiToolProviders` (20).
- 51 SDK cases: `TestApiConnector` (9), `TestClaudeApiConnector` (10),
  `TestDeepSeekApiConnector` (10), `TestClaudeApi` (9), `TestDeepSeekApi` (9), and
  `TestSessionReferenceCompatibility` (4).
- Checkstyle, license checks, strict Javadocs and `-Ppublish` packaging passed.
- The published `0.164.0-1` compatibility checker passed unchanged consumer bytecode,
  public/driver descriptors and shapes, shading, checksum resources and focused packaged
  Claude/DeepSeek fake-HTTP, coding-tool and configured-provider fixtures.
- A separate comparison with the preceding `ea14580` artifact checked 84 existing connector,
  model/tool SPI, resource and facade types. Descriptors, record components, enum order and sealed
  shapes matched exactly; no public additions were needed.
- The version-1 fixture covers every existing execution-journal kind, attachments, private replay,
  tool results and an absent legacy manifest. Negative cases cover duplicate keys, trailing input,
  missing/null/coerced fields, invalid phases/versions, malformed calls and duplicate receipts.
- An alternate in-memory repository and instruction assembler run through the real engine and
  tool loop; a null repository cannot publish a session or consume capacity.

Verification used a temporary source snapshot excluding the four unrelated Claude CLI edits.
Their SHA-256 values were checked before/after. Maven used `-o -T1`, one Surefire fork, disabled
JUnit parallel execution, low process priority and
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`.
The tested shaded candidate SHA-256 is
`a254af9f16e5ef495e8cfd43e365d3105dca95f057591dc5df7ece8945298d30`.

No agents, live inference, provider CLIs, broad application suites, Bytequay suites, Xcode builds
or simulators were started. These results establish the exercised compatibility and lifecycle
behavior; they are not a DSH runtime integration or a performance benchmark.

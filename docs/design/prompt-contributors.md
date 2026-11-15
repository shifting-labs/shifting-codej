# Scoped prompt and context contributors

Status: implemented and verified. Follows [conversation history](conversation-history.md) and
the [DeepSeek study](deepseek-harness-study.md). Existing APIs and default prompt behavior remain.

## Contracts and scope

`PromptProvider<C>` binds immutable configuration explicitly and opens a fresh `PromptContributor`
for each underlying driver session. `PromptProviderBinding` captures a validated stable provider ID
without Class tokens. Configured provider order and each contribution's section order are stable;
duplicate provider IDs and duplicate section IDs within one contribution are rejected. Section
identities are namespaced by provider, so independent providers can use the same local section ID.

`PromptSessionContext` exposes session identity, connector identity, worktree, access mode and
shutdown timeout. `PromptContext` exposes the selected model/effort, turn identity, staged input,
known history, deadline and cancellation state. Neither exposes tool handlers, approvals, a process
launcher, a model client or an authority-changing operation. These are trusted Java extensions;
their interfaces do not sandbox arbitrary host code. Contributors may perform bounded read-only
context retrieval and must cooperate with interruption/cancellation. They perform no inference or
model-requested tool effects.

`PromptContribution` carries separate ordered instruction and context sections, each a validated
`PromptSection`. Instructions append to the existing standing/managed prompt. Context is rendered
with source labels before the original user text in `ModelInput.UserMessage`; it never becomes a
system instruction or a tool result. Model, effort, tier, tool definitions and permissions remain
engine-owned. No contributions means byte-for-byte legacy prompt and input behavior.

## Capture and persistence

Capture runs once per public turn, after reconciling any pending tool batch and before new request
admission. A turn reuses the captured instruction envelope and the provider's ordinary replay carries
its initial contextual user input through tool continuation. Later turns capture fresh context.
An interrupted pending batch uses its saved setup; new contributors cannot rewrite its model,
instructions or tool definitions. Caller input remains a separate execution-journal fact; actual
delivered contextual input enters the provider-neutral history on response settlement.

The existing format-2 checkpoint already persists the current/pending instruction envelope and
the actual submitted input. No checkpoint version or public record shape changes are required.
Full historical instruction provenance, per-inference-step refresh, mid-turn registry replacement
and additional context message roles remain separate work.

Across all providers, a turn permits at most 64 contributed sections and 65,536 rendered contribution
characters. Each contribution also checks these limits before rendering. The combined contextual
user input respects the engine's existing text bound. No content is silently truncated. Existing
base instructions retain their legacy limits/behavior when contributors are absent.

## Ownership and failure

Session initialization is tracked before factories open, with the existing blocked checkpoint gate
while resources are acquired. A provider cleans up if open fails before returning; after return,
the session owns the contributor immediately. Close runs in reverse acquisition order after the
turn and callbacks stop. Failed cleanup retains ownership and writer/session claims for retry.
Reentrant shutdown during startup or release cannot report premature quiescence.

Contribution callbacks use the existing bounded turn worker; no executor or agent is added.
Cancellation interrupts a running contributor and is also observable through `PromptContext`.
A non-cooperative callback keeps its session owned; bounded close reports failure until it returns.
Assembly failure or invalid/oversized content stops before model admission. Exceptions and default
diagnostics never render contribution/configuration text. Closing borrowed host services is forbidden.

## Plan

- [x] Add typed prompt/context SPI values, session/request views and provider binding.
- [x] Add explicit provider installation to engine and Claude/DeepSeek API builders.
- [x] Implement isolated session ownership, deterministic capture, bounds and safe cancellation.
- [x] Preserve saved pending-tool setup, permissions, native replay and default behavior.
- [x] Add SPI baseline, lifecycle/isolation/failure, recovery and facade fake-HTTP coverage.
- [x] Verify focused tests, packaging and existing API descriptors/shapes; preserve unrelated edits.
- [x] Record validation, update the main plan and commit.

## Validation

The final isolated snapshot passed **201 focused cases**, with zero failures, errors or skips:

- 146 CLI cases: `TestPromptSpiValues` (4), `TestApiAgentDriver` (46),
  `TestApiCheckpointCodec` (33), `TestApiCheckpointMigrations` (25), `TestApiSessionStore` (6),
  `TestToolDispatcher` (12), and `TestApiToolProviders` (20).
- 55 SDK cases: both API facades (10 each), both provider connector baselines (11 each),
  `TestApiConnector` (9) and `TestSessionReferenceCompatibility` (4).
- Checkstyle, license checks, strict Javadocs and `-Ppublish` shaded packaging passed.
- Published `0.164.0-1` binary/API consumers, record/enum/sealed shapes, shading/checksum resources
  and packaged fake-HTTP/coding/provider fixtures passed. The JDK-only provider consumer also
  installs the prompt SPI and verifies instruction/context separation through tool continuation.
- A comparison with `a5caac6` checked 90 existing connector/model/tool/resource/config/facade types.
  Existing descriptors and shapes matched. The only reviewed additions to existing classes were
  eight `promptProvider` builder overloads across the engine, provider configs and API facades.
- Coverage includes fresh session scopes, immutable configuration/sections, duplicate IDs, rendering
  order, aggregate section/text budgets, input limits, null/throwing contributors, reverse cleanup,
  cleanup retries, retained writer/capacity claims, non-cooperative cancellation, saved-prompt recovery,
  unchanged permissions, startup shutdown reentry and recursive cleanup. Fake HTTP verifies role
  separation and context replay after restart for both Anthropic and DeepSeek.

Tests used an isolated source snapshot excluding four unrelated Claude CLI edits, whose SHA-256
values remained unchanged. Maven ran offline with `-T1`, one Surefire fork, JUnit parallel execution
disabled, low priority, and `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'`.
The tested shaded candidate SHA-256 is
`a70d120250e170459ef0094c62182f3dddf062ffe7a2f1cf3205146410a7b649`.

No agents, live inference, provider CLIs, broad application/Bytequay suites, Xcode builds or simulators
were started. Native tool fixtures are limited to the existing focused packaged containment check.
This stage supplies explicit per-driver-session installation and per-public-turn capture; it does
not supply mid-turn refresh, hot reload, a CLI export adapter or complete historical prompt provenance.

# Contributing

## Build

You need Java 25 and Maven 3.9. Maven 4 cannot publish to Maven Central yet.

```bash
mvn verify
```

This builds the three modules and runs their tests, Checkstyle and the license-header check.

```bash
mvn -Ppublish package -DskipTests
```

This builds what a release publishes, in `shifting-codej/target/`: the jar with Guava and Jackson
renamed inside it, its sources and Javadoc, and `dependency-reduced-pom.xml`, which names no
dependency.

## Commits

A commit message names no AI agent as an author: no `Co-Authored-By:` trailer for Claude, Codex or
any other agent, and no generated-by line. The person who submits the change is its author. This
applies to commits an agent writes on someone's behalf.

## Modules

- `shifting-codej-api`: the public types. It imports neither of the other modules' packages;
  Checkstyle refuses them.
- `shifting-codej-cli`: the Codex App Server client, the Claude CLI driver, the MCP server for the
  application's tools, and process supervision. Internal.
- `shifting-codej`: the CLI and direct API facades, provider adapters, the Codex download, and the published jar.

Every Java file starts with `src/main/license/header.txt`, and `src/main/checkstyle/checks.xml`
holds the style.

## Connector contract tests

Connector implementations inherit shared contracts, following the same pattern as Trino's
base connector tests. The bases live in `shifting-codej/src/test/java/io/github/shiftinglabs/codej/sdk/testing`:

- `AbstractTestConnector<C>` checks factory configuration types, stable identities, independent
  driver instances, null-input rejection, side-effect-free validation, idempotent close,
  borrowed-service ownership and preservation of saved history.
- `AbstractTestCliConnector<C>` extends that contract with an explicit missing executable.
  Construction must not start or install it. Implement `connector()` and `configuration(Path)`;
  `TestCodexConnector` and `TestClaudeConnector` show the concrete bindings.
- `AbstractTestConnectorDriver` checks metadata, catalogs, quotas, model-specific probing,
  resume/model-aware review capability queries and provider failure propagation. Bind an
  `AgentDriver` delegate through `connector(AgentDriver)`; both existing driver suites inherit it.

These are reusable test-source bases, not new runtime interfaces or a separately published test kit.
Provider-specific tests retain the session, turn, permission, lifecycle and authentication cases
that cannot be required of every connector.

[The executable consumer example](shifting-codej-cli/src/test/java/io/github/shiftinglabs/codej/cli/spi/consumer/TestConnectorSpi.java)
is in a different package and imports only public SPI types. It shows a host creating a typed
connector, validating before opening, consuming normalized events, waiting for a turn result,
persisting a session reference, resuming it and closing owned resources. Its in-memory provider
also exercises the backward-compatible defaults that a minimal third-party driver inherits.

| API surface | Baseline coverage |
| --- | --- |
| `AgentConnector.id`, `configurationType`, `create` | Factory base, concrete factory suites and consumer example |
| `ConnectorContext` constructor and all three accessors | Consumer example, including null/relative paths and unchanged service/path identities |
| `ConnectorIdentity` constructor and both identifiers | Consumer validation cases and stable factory identity contract |
| `ConnectorDriver.identity`, `validate`, `close` | Factory/driver bases and concrete validation, ownership and shutdown tests |
| Concrete factories, `CodexConnectorConfig` and Codex install/version diagnostics | Public-constructor smoke test and existing configuration/authentication/install-failure cases |
| `AgentDriver.id`, `label`, `capabilities`, both `probe` overloads | Driver base and minimal-driver defaults |
| `AgentDriver.models`, `quota`, both `reviewChildAgents` overloads | Driver base and minimal-driver defaults |
| `AgentDriver.open` | Consumer lifecycle/resume example and concrete context/validation tests |
| `AgentSession.reference`, `start`, `close`, reuse/status methods | Consumer defaults and concrete Codex session forwarding/lifecycle tests |
| `RunningTurn.reference`, `completion`, `setup`, `steer`, `cancel` | Consumer defaults and existing SDK turn tests |

Run only these in-memory/fake-provider suites with one test worker:

```bash
mvn -pl shifting-codej -am -Dcodej.test.forks=1 \
  -Djunit.jupiter.execution.parallel.enabled=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TestConnectorSpi,TestCodexConnector,TestClaudeConnector,TestCodexConnectorDriver,TestClaudeConnectorDriver verify
```

`codej.test.forks` retains the existing default of four for ordinary builds. The command above
uses one fork and disables JUnit parallel execution. These suites do not launch a native CLI or
run process stress tests.

## Direct model SPI contracts

The [direct API connector design and checkbox plan](docs/design/direct-api-connectors.md) separates
one inference request from the shared agent engine. The additive SPI is in `cli.spi.model`;
the shared engine is implemented in `cli.api`; the Anthropic SDK adapter and additive `ClaudeApi`
facade live in the SDK module. Tests use fake HTTP, never a live account.

`shifting-codej/src/test/java/io/github/shiftinglabs/codej/sdk/testing/AbstractTestModelClient.java`
is a reusable test-source base. Bind `client()`, `request()`, `expectedResponse()` and
`expectedTextDeltas()` to a deterministic fixture. `pendingCall(client)` arranges a request held
until cancellation; `failedCall(client)` arranges a connection loss with uncertain acceptance.
Real adapters should bind these hooks through a fake HTTP transport and add wire-specific tests.
The base covers metadata, ordered streaming, final replay/usage, idempotent cancellation/close,
independent calls, normalized failures, null inputs and event-consumer failure. It is not a
separately published test kit.

[TestModelClientSpi](shifting-codej/src/test/java/io/github/shiftinglabs/codej/cli/spi/model/consumer/TestModelClientSpi.java)
binds that base without threads, processes or network. Its external consumer example retains a
Java `Tool` in the host, sends only its schema to the adapter, receives a tool request, invokes the
handler and continues inference with a copied replay checkpoint and matching tool result. It is
an SPI usage example; persistence, permissions and the agent loop are not implemented by this fixture.

| New API surface | Baseline coverage |
| --- | --- |
| `ModelClient` capabilities, probe, catalog/quota defaults, generate and close | Shared base and scripted binding |
| `ModelCall` completion/cancel; `ModelEventSink.textDelta` | Ordered output, completion, cancellation, failures and ownership contracts |
| `ModelRequest` builder, all options/accessors and schema-only definitions | `TestModelSpiValues` plus external tool continuation |
| `ModelInput` user messages, attachments, tool batches and error results | Value validation/copy tests and consumer example |
| `ModelResponse` accessors, tool calls, all stop reasons and final usage | Value tests and shared response contract |
| `ModelCapabilities` constructor, conservative factory and all accessors | Defensive copying, invalid values and default coverage |
| `ModelReplayState` constructor, all accessors and masked diagnostics | Lossless round-trip, version/identity validation and consumer continuation |
| `ModelClientException` constructor, kind and delivery uncertainty | All failure kinds, invalid values and shared failure contract |

Run only these suites and the existing legacy SPI consumer with a constrained JVM:

```bash
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC' \
  nice -n 10 mvn -o -T1 -pl shifting-codej -am \
  -Dcodej.test.forks=1 -Djunit.jupiter.execution.parallel.enabled=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TestModelSpiValues,TestModelClientSpi,TestConnectorSpi verify
```

Offline mode requires dependencies already cached. This selects no native CLI or process-stress
tests and does not run broad SDK or downstream test suites.

## Shared API engine contracts

`AbstractTestApiConnector<C>` extends the existing factory contract with text turns, normalized
events, restart/resume, a schema-only host-tool round trip and rejection of foreign references
and unsupported filesystem constraints. Override
`contractSessionContext()` when a connector's validation baseline differs from the CLI default;
the existing CLI bindings retain their read-only context. `TestApiConnector` binds the API base
to a fake ModelClient and uses only public connector/session/turn types.

`TestApiAgentDriver` and `TestApiSessionStore` cover the actual engine loop, durable replay and
receipts, writer exclusion, approval/denial, schema enforcement, input staging, limits, error
mapping, cancellation races, callback shutdown and borrowed resources. These tests use tiny
in-memory model fixtures and temporary files, never native processes or live provider requests.

Run the engine and affected pure contract suites with one constrained test JVM:

```bash
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC' \
  nice -n 10 mvn -o -T1 -pl shifting-codej -am \
  -Dcodej.test.forks=1 -Djunit.jupiter.execution.parallel.enabled=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TestApiAgentDriver,TestApiSessionStore,TestApiConnector,TestModelSpiValues,TestModelClientSpi,TestConnectorSpi,TestCodexConnector,TestClaudeConnector verify
```

This explicitly selected run compiles the reactor but runs no broad SDK, Bytequay, native CLI or
process-stress suites. Keep unrelated working-tree edits out of the tested snapshot and commit.


`TestClaudeApiConnector` binds the same API connector base to `AnthropicHttpFixture`.
`TestAnthropicModelClient` inherits the model-client contracts and tests actual SDK HTTP/SSE:
explicit credential rotation, metadata, lossless signed replay, fragmented tool arguments, ordered
results/errors, images, stop reasons, cache usage, transport timeouts, redirects and normalized
failures without retries. `TestClaudeApi` exercises the public facade, including a borrowed callback
executor and cancellation while a host handler remains active. No key or CLI is needed.

Run those suites with `-Dtest=TestAnthropicModelClient,TestClaudeApiConnector,TestClaudeApi`
and the same serial JVM flags above. `mvn -Ppublish package -DskipTests` checks packaging and
Javadocs. Then run `scripts/check-compatibility.py --candidate <shaded-jar> --output <evidence-dir>`
with the constrained `JAVA_TOOL_OPTIONS`: it also compiles `ClaudeApiConsumer.java` against the
candidate and runs fake HTTP metadata, streaming, signed tool replay, key rotation and durable
resume with only the JDK and jar. This catches SDK/Jackson/Kotlin relocation problems that a
reactor test classpath hides. The SDK mapper uses its explicit Jackson creators and preserves
missing versus null fields without Kotlin reflection; all provider dependencies remain relocated.

`TestDeepSeekModelClient` binds the same model-client contracts to the Chat Completions protocol.
`TestDeepSeekApiConnector` inherits the same API connector base, and `TestDeepSeekApi` demonstrates
unchanged public session/tool APIs. Its tests cover private reasoning replay, interleaved fragmented
tool calls, exact result IDs/order, effort, metadata, cancellation, deadlines and failures without
retries. The existing Codex-backed DeepSeek lane is not used by these fixtures.

`ApiHttpTransport` owns the bounded JDK HTTP resources and whole-response deadlines shared by the
Anthropic SDK bridge and the direct DeepSeek adapter. Provider adapters retain separate framing,
replay and error translation. `DeepSeekApiConsumer.java` extends the jar-only packaging check with
metadata, native tool messages, private reasoning replay, key rotation and durable resume.

Run `-Dtest=TestDeepSeekModelClient,TestDeepSeekApiConnector,TestDeepSeekApi,TestAnthropicModelClient,TestClaudeApiConnector,TestClaudeApi`
with the constrained serial JVM flags above. Include the existing shared-engine/SPI suites before
committing changes to shared HTTP behavior. No new dependency is needed by the DeepSeek adapter.

## Versions

A release is `<Codex.VERSION>-<N>`:

- `0.164.0-1` is the first release on Codex 0.164.0, and `0.164.0-2` a codej fix on the same Codex.
- `0.164.0-1-rc1` is a release candidate.
- There is never a plain `0.164.0`, which Maven would sort before the candidates.

`TestVersion` fails the build when the version breaks this rule. The version cannot signal an API
break, so an intended one is deprecated first and removed no sooner than two releases later, with
a note in the release notes.

## Moving to a newer Codex

Following every Codex release is not required; a bump is made when it is worth it.

1. Update `Codex.VERSION` in `shifting-codej/src/main/java/io/github/shiftinglabs/codej/Codex.java`.
2. Run `node scripts/codex-checksums.mjs --arch arm64`. It downloads OpenAI's npm package, checks
   npm's integrity, and prints the lines for
   `shifting-codej/src/main/resources/io/github/shiftinglabs/codej/sdk/impl/codex-checksums.properties`.
3. Re-pin `scripts/codex-app-server-compat.mjs` and regenerate the App Server fixture with
   `node scripts/codex-app-server-compat.mjs --update-bindings`.
4. Set the version to `<new Codex.VERSION>-1-SNAPSHOT`.

## Releasing

The **Release** workflow (Actions → Release → Run workflow) takes a version, checks its form,
sets it, builds and tests, signs every file, and uploads the deployment to Maven Central. Nothing is
published until a maintainer presses **Publish** on the Central Portal's Deployments page.

It needs four repository secrets: `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD`, a Central
Portal user token, and `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`, a signing key whose public half is on
`keyserver.ubuntu.com`.

CI also downloads Codex through the published jar on every push, and checks the recorded checksums
and the App Server protocol against OpenAI's package.

## Built-in API coding tools

The focused command below includes record validation, no-follow file boundaries, patch preimages,
read-only access, approvals, cancellation/uncertain shutdown, and fake HTTP facade round trips.
`TestSandboxCommand` uses short, temporary macOS processes to verify actual Seatbelt denial of
outside reads/writes, links, subprocesses and network, plus command/output limits. It is not a
process-stress suite. Other platforms fail closed when coding tools are requested.

```sh
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC' \
  nice -n 10 mvn -o -T1 -pl shifting-codej -am \
  -Dcodej.test.forks=1 -Djunit.jupiter.execution.parallel.enabled=false \
  -Dtest=TestCodingTools,TestWorkspaceFiles,TestSandboxCommand,TestApiAgentDriver,TestClaudeApi,TestDeepSeekApi \
  -Dsurefire.failIfNoSpecifiedTests=false verify
```

See [the design and checklist](docs/design/coding-tools.md). Packaging with `-Ppublish` and the
compatibility checker also exercises coding tools from the shaded jar on macOS, using only a
fake HTTP server and temporary workspace; it starts no provider CLI or live inference.

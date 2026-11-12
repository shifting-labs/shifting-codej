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

## Modules

- `shifting-codej-api`: the public types. It imports neither of the other modules' packages;
  Checkstyle refuses them.
- `shifting-codej-cli`: the Codex App Server client, the Claude CLI driver, the MCP server for the
  application's tools, and process supervision. Internal.
- `shifting-codej`: the `Codex` and `Claude` builders, the Codex download, and the published jar.

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
run process stress tests. A future direct-model SPI needs its own contract base when that SPI
is implemented; no `ModelClient` API is claimed or introduced here.

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

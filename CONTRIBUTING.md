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

The release workflow takes a version, checks it, signs every file, and uploads the deployment to
Maven Central. Nothing is published until a maintainer presses **Publish** on the Central Portal's
Deployments page.

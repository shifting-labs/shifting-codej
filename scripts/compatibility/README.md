# Published artifact compatibility

Requirements: Python 3, JDK 25 or newer (`java`, `javac`, `javap`; optionally set `JAVA_HOME`),
the cached published `io.github.shifting-labs:shifting-codej:0.164.0-1` jar, and a fresh candidate
built with the existing `publish` profile. No additional Python or Maven dependencies are added.

```sh
python3 scripts/check-compatibility.py \
  --candidate /absolute/path/to/shifting-codej-0.164.0-1-SNAPSHOT.jar \
  --output /tmp/codej-compat-final
```

Use `--baseline /absolute/path/to/published.jar` to override the default cached baseline.
The checker does not run Maven, download anything, or install artifacts. It writes evidence only
to `--output` and temporary fixture/state directories. Use an output directory outside the checkout.
Run it with the same JDK used to build the candidate.

The check inventories classes from the **baseline**, so new SPI types are additive. It compares:

- Public/protected JVM descriptors, constructors, generic signatures, inheritance, default methods
  and constant declarations/compile-time values for the public API (including both builders),
  the legacy `cli.driver` package, and `ToolServer` exposed by the builders.
- Record component names/order/types, enum values/order, sealed status and permitted subclass names,
  and public runtime constants.
- Relocated Jackson/Guava sentinel classes, and absence of unrelocated `com/google` or `com/fasterxml`
  class entries (including multi-release entries), to catch unshaded dependency collisions.
- Exact bytes at `io/github/shiftinglabs/codej/sdk/impl/codex-checksums.properties`, relative
  resource lookup from `CodexBinary`, archive/file checksum forms, and the existing release-version
  convention using the runtime `Codex.VERSION` field rather than its inlined value.
- A legacy driver/session/turn implementation and public builder calls compiled **once against
  the baseline only**, then executed with unchanged class bytes on the baseline and candidate in
  separate JVMs. Runtime classpaths contain only those fixture classes and one shaded jar.
- Exact `provider:<numeric contract>:<native value>` text, including embedded colons and Unicode,
  and Bytequay-style numeric parsing. `TestSessionReferenceCompatibility` separately exercises
  the actual SDK encode/decode bridge, account lanes and malformed references.

The standalone smoke test constructs and closes both providers with explicit temporary state and
missing executables. It does not probe providers, run model turns, read credentials, download
Codex, touch a database, or prove all provider behavior. Signature/source compatibility alone
does not prove behavioral compatibility. The surface snapshot is intentionally strict: edits
within existing types (even compatible additions/reordering) need review; new types are allowed.

## Build isolation and final gate

When other workers own the checkout, package a temporary **copy** of the latest stable sources,
excluding `.git` and all `target` directories. Use a separate copied Maven cache (ordinary copies
or copy-on-write clones; never symlink/hardlink it to the default repository). For example, in
that temporary source copy, with an already populated isolated cache:

```sh
mvn -o -Dmaven.repo.local=/tmp/codej-compat/repository -Ppublish -DskipTests package
mvn -o -Dmaven.repo.local=/tmp/codej-compat/repository -pl shifting-codej -am \
  -Dtest=TestSessionReferenceCompatibility -Dsurefire.failIfNoSpecifiedTests=false test
```

Do not run those commands in the shared checkout, install a candidate into the default Maven
repository, change Bytequay's tracked dependency/version, or invoke `deploy` or the `release` profile.
For downstream Maven validation, use a temporary Bytequay copy and another isolated cache, stage
the candidate at the unchanged `0.164.0-1` coordinate there, and record the exact tested modules.
Bytequay's checksum reader uses the default home-relative cache path, so its execution also needs
an isolated home resolver or equivalent test setup; changing `maven.repo.local` alone is insufficient.

Record candidate/source hashes and the tested snapshot. Intermediate results must be rerun on the
coordinator's final integrated shaded jar, together with affected unmodified Bytequay tests.
`summary.json`, signature/shape snapshots, diffs and fixture outputs identify what actually passed.

## Focused downstream validation

Keep the consumer's dependency declaration and test sources unchanged. In its temporary source
copy, select only tests that cross the SDK boundary: session references/events, host-tool schemas,
lane factories, accounts, catalog/quota metadata and text helpers. Stage the self-contained
candidate jar alongside the existing published POM at the consumer's unchanged coordinate in an
isolated repository. Verify the staged jar hash and the Surefire reports' actual classpaths.

A large shared cache need not be copied. A temporary Maven settings file can name the isolated
local repository and mirror `*` to a `file:` URL for the existing cache. Maven then copies only
required artifacts into the isolated repository; the original cache remains read-only. Do not use
offline mode while populating through this file mirror. No external mirror is needed when all
selected dependencies are already cached.

Limit Maven to `-T1`, disable JUnit parallel execution and set one Surefire fork. Check effective
configuration: a consumer's literal `<forkCount>4</forkCount>` overrides a command-line property.
Change that literal only in the temporary copy and record the execution-only difference. Use
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=1 -Xmx512m -XX:+UseSerialGC'` and low process priority.
Prepare only required upstream modules with tests skipped, then verify the selected consumers.

For a Node packaging reader that resolves `os.homedir()`, an isolated process can override that
resolver and call `syncBuiltinESMExports()` before importing the unchanged reader. This lets it
read the staged candidate without changing the user's home or default Maven cache. Compare the
reader's supported-architecture inventory with the candidate's real checksum resource, including
empty inventories for unsupported architectures. Run local bundle fixture tests serially; do not
start a native build or download a provider executable for this check.

The [direct API connector plan](../../docs/design/direct-api-connectors.md#downstream-integration-validation)
records the completed scoped run, tested revisions, counts and candidate hash. Full application
and live-provider behavior remain outside these checks.

## Checker controls

```sh
python3 scripts/compatibility/test_checker.py
```

These standard-library tests cover descriptor mismatches, missing relocation sentinels, unrelocated
dependency entries, and an added sealed subtype that `javap -protected -s -constants` cannot see.
The sealed control uses the cached baseline and JDK, with all compilation in temporary directories.

Run the published jar as both baseline and candidate to test the checker/fixture independently of
the refactor. As a negative control, copy that jar to a temporary file without the checksum resource
and confirm the checker exits nonzero with `success: false`. Keep all controls outside the checkout.

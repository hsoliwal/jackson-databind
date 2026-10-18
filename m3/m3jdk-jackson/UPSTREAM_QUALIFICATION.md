<!-- SPDX-License-Identifier: Apache-2.0 -->
<!-- Copyright 2026 Hitesh Soliwal and contributors. -->

# Jackson upstream qualification

`upstream-qualification.py` runs the pinned Jackson 2.19.2 upstream suites and then
runs the same compiled tests against the exact Jackson JARs produced by the M3JDK
source build. It checks test selection, failures, inherited skips, module
descriptors, artifact hashes, and JVM class-load origins. A successful fork build
alone does not qualify the generated runtime artifacts.

The command never edits an upstream POM or source file. Maven rebuilds the normal
`target` directories in the three fork checkouts. Receipts, copied test classes,
replay POMs, and class-load logs go into a separate, new `--out` directory.

## Source pins

| Fork | Upstream release tag | Required upstream commit |
| --- | --- | --- |
| `hsoliwal/jackson-annotations` | `jackson-annotations-2.19.2` | `e84b2adf8f4bb9d881d02a1ec4a0c70dcc9c5a00` |
| `hsoliwal/jackson-core` | `jackson-core-2.19.2` | `1fb76534cd69a94ecbfb23ed6dce334109c1230c` |
| `hsoliwal/jackson-databind` | `jackson-databind-2.19.2` | `1a506043e0644c9cf60c0905474459605fc751d0` |

Each pin must be available locally and be an ancestor of the checked-out fork
HEAD. Every file tracked by the upstream pin must remain identical. The only
permitted additions are committed files under `m3/m3jdk-jackson/`; the working
tree must otherwise be clean. The receipt records the fork HEAD, upstream commit,
and upstream tree separately. CI checkout must fetch the pinned ancestor objects,
for example with `fetch-depth: 0`. This allows generated integration payloads in
the forks while preserving the exact upstream source and test baseline.

## Run

Use Python 3.9 or newer, JDK 21, Maven, the three fork checkouts, and a completed
M3JDK Jackson source-build output. The generated JAR directory must contain
`com.fasterxml.jackson.annotation.jar`, `com.fasterxml.jackson.core.jar`, and
`com.fasterxml.jackson.databind.jar`. The source-build `RESULT.json` must report
`status: PASS`, `source_build: PASS`, and `upstream_version: 2.19.2`; each JAR must
match the SHA-256 recorded there.

```sh
python3 upstream-qualification.py \
  --annotations-root /work/forks/jackson-annotations \
  --core-root /work/forks/jackson-core \
  --databind-root /work/forks/jackson-databind \
  --runtime-jars /work/m3-jackson-runtime/jars \
  --java-home /opt/jdk-21 \
  --maven /opt/apache-maven/bin/mvn \
  --cache /work/jackson-test-maven-cache \
  --out /work/jackson-upstream-proof
```

`--runtime-receipt` can select a different location for the source-build receipt;
the default is `RESULT.json` in the parent of `--runtime-jars`. `--settings` and
`--global-settings` accept Maven settings files. Without them, the command writes
empty task settings rather than depending on a user's private Maven settings.
`--trust-store` supplies a Java TLS trust store when the build network requires
one. TLS verification is not disabled.

Add `--offline` once the full upstream build and test dependencies are cached.
Those dependencies include upstream test tools such as JUnit, Mockito, Byte
Buddy, AssertJ, and Micronaut's type-pollution tool. They are qualification
dependencies and are not added to the M3JDK runtime module pack. Upstream core's
tests also directly exercise unshaded FastDoubleParser 1.0.90; the replay records
that test-only JAR and its loaded classes separately from the source-built,
relocated FastDoubleParser inside the generated core JAR.

## Test selection and environment

The eight lanes run sequentially: three full upstream `clean install`
lifecycles, three generated-artifact replays, and the separate upstream and
generated-artifact type-pollution runs. The command exposes no arbitrary test
filter or skip option.

| Suite, in each runtime lane | Reported cases | Passed | Upstream skips |
| --- | ---: | ---: | ---: |
| Annotations | 60 | 60 | 0 |
| Core | 1,470 | 1,468 | 2 |
| Databind Java 21 `PrimarySuite` | 3,601 | 3,601 | 0 |
| Main suites combined | 5,131 | 5,129 | 2 |
| Additional isolated `TypePollutionSuite` | 3 | 2 | 1 |

These are required counts for the pinned sources on JDK 21, not merely an
informational summary. Zero tests, missing cases, new skips, test failures,
errors, and flaky/rerun failures or errors produce a nonzero exit. The generated run must
have exactly the same testcase multiset as its successful upstream run.

Annotations' release POM asks for Java 6 source and target levels, which JDK 21
does not support. Its full lifecycle receives the four documented Java 8
compilation properties: `javac.src.version`, `javac.target.version`,
`maven.compiler.source`, and `maven.compiler.target`. The POM remains unchanged.
Databind uses its existing Java 21 profile, including its JDK 11, 17, and 21 test
sources. The ordinary core and databind lifecycles retain their upstream JaCoCo
and package/verify/install goals.

The two inherited core skips are:

- `com.fasterxml.jackson.core.io.NumberOutputTest#divBy1000FullRange`
- `com.fasterxml.jackson.core.read.TestReadHumongousString#testLargeStringDeserialization`

Databind's own POM and `PrimarySuite` exclude `MapperFootprintTest`. The primary
suite also excludes the type-pollution package. This command separately invokes
the pinned POM's declared `type-pollution-test` execution using `-Pjava17` and the
already compiled Java 21 tests; it does not rebuild them at a different Java
release. Its disabled `sample` method is an intentionally failing demonstration,
so the required extra result is two passed tests and that one inherited skip.
The mapper footprint test remains explicitly unselected by the upstream
configuration and is not claimed as passing.

Mockito's inline mock maker and the type-pollution tool require working Java
agent attachment. A sandbox that blocks the Byte Buddy helper process will
produce a nonzero prerequisite failure. Run with an execution environment that
supports the upstream test machinery; do not suppress those tests or change the
mock implementation to obtain a passing receipt.

## DNS prerequisite and recorded fixtures

Two unchanged databind `InetAddress` tests resolve `google.com`. A machine with
working system DNS needs no extra options. If system DNS is unavailable, the
command accepts a recorded successful DNS response with its provenance:

```sh
python3 upstream-qualification.py \
  --annotations-root /work/forks/jackson-annotations \
  --core-root /work/forks/jackson-core \
  --databind-root /work/forks/jackson-databind \
  --runtime-jars /work/m3-jackson-runtime/jars \
  --java-home /opt/jdk-21 \
  --maven /opt/apache-maven/bin/mvn \
  --cache /work/jackson-test-maven-cache \
  --out /work/jackson-upstream-proof-with-dns \
  --dns-response /work/fixtures/google-dns.json \
  --dns-provenance /work/fixtures/DNS-FIXTURE.json \
  --offline
```

Obtain the response through a TLS-verified request to
`https://dns.google/resolve?name=google.com&type=A`. The provenance JSON must
contain `url` with that exact URL, `retrieved_utc` as an ISO timestamp, and
`dns_sha256` with the SHA-256 of the exact response bytes. The response must have
`Status: 0` and valid IPv4 A records for `google.com.`. Record the actual response;
do not invent an address or silently replace the hostname in the tests.

The runner copies the response and provenance into the evidence directory,
copies the host machine's hosts entries into a task-local file, and appends the
recorded addresses. Its receipt includes hashes of the response, provenance,
base hosts data, and resulting fixture, along with the source URL and retrieval
time. The system hosts file is not modified. The fixture is a recorded test
input; it is not a claim that the historical DNS answer remains current.

The setting is passed through `JAVA_TOOL_OPTIONS` as
`-Djdk.net.hosts.file=<task-local-file>` **at JVM startup**. Promoting this property
later through Surefire is insufficient when `InetAddress` has already initialized.
The original environment failures should be retained when an existing run needs
this correction.

## Artifact and class origin checks

Before testing, the runner validates each generated JAR's hash against the
source-build receipt and checks its explicit module name/version with JDK 21's
`jar --describe-module`. The replay uses Surefire 3.5.3 and the dependency closure
recorded by the successful upstream tests. It removes all original Jackson
runtime entries, supplies the generated JARs, and preserves the compiled test
snapshot and upstream test selection.

JVM class-load logs verify every loaded production class present in those JARs,
including the relocated FastDoubleParser classes. Test-only unshaded
FastDoubleParser classes are verified against their separately recorded 1.0.90
dependency. Required classes must actually load; correct classpath declarations
alone cannot pass the origin gate.

The pinned tests intentionally instrument several already loaded classes.
Mockito targets `TypeFactory` and `NumberInput`; the type-pollution tool
retransforms `ObjectMapper`, `ObjectCodec`, `TreeCodec`, and `Versioned`. A JVM
`__VM_RedefineClasses__` event is accepted only for the corresponding explicit
test targets and only after a verified original load from the generated JAR.
Those events are recorded separately and never replace the original artifact
origin in the receipt.

## Receipts and scope

The root `RESULT.json` has `status: PASS` only after all eight lanes pass. The
command prints `JACKSON_UPSTREAM_QUALIFICATION_PASS` and exits zero. Any failure,
missing prerequisite, source mismatch, artifact mismatch, or interruption keeps
a nonzero exit and the completed evidence. Existing nonempty output directories
are refused so previous receipts cannot be overwritten.

`runtime_jars` records each component's module, path, SHA-256, and descriptor.
`forks` records exact source identities. `lanes` maps each lane/component to its
receipt, receipt hash, status, and totals. Each lane retains Maven logs and
Surefire XML; generated lanes additionally retain replay POMs, dependency
hashes, test selection comparisons, and class-load origin checks. The summary
contains `main_suite_totals_per_lane` and `additional_typepollution_per_lane`.

Dependency hashes are captured with the successful upstream baseline. The runner
rechecks those bytes, copied test classes, generated JARs, source-build receipt,
its own executable, and fork identities before and after generated testing and
again before the overall result can pass. A correct path with changed contents
cannot reuse an earlier artifact hash in a passing receipt.

`qualification_tool_sha256` binds the exact runner bytes. Generated lane receipts
include `classload_logs` with each log's name and SHA-256. The shared read-only
`inspect_fork` and `inspect_origins` functions let the final receipt verifier
apply the same source and origin policy. For a later byte-identical runtime
artifact, `inspect_origins` accepts `verification_path` for reading bytes while
retaining the recorded `path` as the expected original JVM load location.

The admission counterexamples are preserved in
`upstream-qualification-regression.py`. Run it with Python alongside the runner:

```sh
python3 upstream-qualification-regression.py
```

The checks reject all four Surefire retry tags even when aggregate failures and
errors are zero; reject mutations to the runner, receipt, runtime JAR, test
snapshot, dependency JAR, or fork HEAD between custody checks; and verify that
later artifact locations require identical bytes and the recorded original load
origin. These small detector regressions do not replace the eight Maven lanes.

This qualifies the source-built Jackson library artifacts under stock JDK 21 on
the classpath. M3File/M3ObjectMapper bridge contracts, M3AST compiler callbacks,
linked JPMS images, the complete custom M3 VM, and M3String flag-on/flag-off
qualification remain separate gates. The upstream tests instantiate upstream
Jackson classes; their successful results do not substitute for those integration
contracts. A later runtime build can reuse this upstream compatibility evidence
only when the three Jackson JAR SHA-256 values match exactly.

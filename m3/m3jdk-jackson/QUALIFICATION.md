<!-- Copyright 2026 Hitesh Soliwal and contributors. SPDX-License-Identifier: Apache-2.0 -->
# Jackson JMOD qualification

This pack builds the full existing Jackson 2.19.2 implementation and qualifies its
M3 input/output, prepared-binding and compiler integration. Results identify the
exact source files, library bytes, toolchain, module closure and executed processes.
The optional module does not promote the repository-wide String, array,
collection or compiler receiving sequence.

## Executed local implementation proof, 2026-10-18

| Gate | Result |
| --- | --- |
| Four existing donor Maven builds | PASS: annotations, core, databind and FastDoubleParser; donor POMs and sources remain unchanged |
| Offline source build | PASS using the resolved build-tool cache |
| Donor source/provenance verification | PASS: 777 source/build/legal files and 753 upstream provenance entries |
| Existing module graph and JMOD assembler | PASS: four runtime modules; five with the compiler bridge |
| Jackson compatibility contract | PASS: 299 checks on the host and the same 299 on the linked image |
| Indexed M3File contract | PASS: 20,595 checks on the host and the same 20,595 on the linked image |
| Indexed output and streaming contract | PASS: 962 checks on the host and the same 962 on the linked image |
| Actual M3 compiler owner closure | PASS: M3AST, M3ASTPC, M3PrimitiveImageCompiler and JavaCompiler compiled together |
| Baseline M3AST callback contract | PASS: one analyzed declaration, one projection computation and one projection hit on host and linked image |
| Extended M3AST contract | PASS: 161 checks, including generic bindings, named nested declarations and separate compiler sessions |
| Upstream annotations suite and generated-JAR replay | PASS: 60 cases in each lane |
| Upstream core suite and generated-JAR replay | PASS: 1,470 reported cases in each lane; 1,468 pass and two upstream-disabled cases |
| Upstream databind PrimarySuite and generated-JAR replay | PASS: all 3,601 cases in each lane |
| Additional isolated type-pollution suite | PASS: two executed cases and one intentionally disabled sample in each lane |
| Custom M3VM storage execution | NOT AVAILABLE on the selected stock Temurin 21.0.8+9 |

The main upstream suites report 5,131 cases per lane, with zero failures/errors and
only the two inherited core skips. Generated replay must retain the exact testcase
multiset. The additional type-pollution run is separate from PrimarySuite. The
upstream-excluded mapper footprint test is not claimed as passing. The complete
command, source pins, skip identities, environment adjustments and class-origin
policy are in `UPSTREAM_QUALIFICATION.md`.

The M3File contract covers adaptive-width thresholds, equal-hash unequal Strings,
empty occurrences, UTF-16/surrogate boundaries, slices, mark/reset/skip and closed
Reader behavior, mutation of the caller's piece list, randomized StringReader
comparison and logical overflow. The streaming contract adds randomized Writer
comparison, bounded admission/output, direct slice transfer, root/array sequences,
generic values, UTF-16 BOMs and early-stop/EOF/failure ownership. It explicitly
tests failures after a parser or generator has been created and preserves primary
exceptions when cleanup fails.

Compiler tests use real attributed M3AST callbacks and detached metadata. They
check 17 named declarations, generic component signatures, nested owners, arrays,
wildcards, annotations, private creators, mismatched kinds/types and independent
source generations. The existing top-level projection identity remains unchanged.

## Source generation and exact-artifact evidence

The original `M3JacksonJmodRecipe` and `m3-jackson-jmod-20261018` packet retain their
original replay. The follow-up `M3JacksonJmodFullRecipe` applies to M3JDK commit
`8c274790d5b7cf2741df7baec96d6c5d0e9c72c4`. `M3JacksonForkRecipe` independently
generates the optional pack under `m3/m3jdk-jackson` in the databind fork at
`1a506043e0644c9cf60c0905474459605fc751d0`. Both use the existing canonical Java,
text and module-descriptor recipe owners in `hsoliwal/com.synexia`.

Every receiver write requires real OpenRewrite SDK output, exact preimages and
postimages, a complete zero-change second pass, drift/occupied-path refusal and
filesystem readback. The application receipt records the selected receiver/source
bases and packet digest. It is separate from compilation and test receipts.

The source build fixes archive entry dates through the pinned Maven plugins'
existing `project.build.outputTimestamp` property. It never rewrites a donor POM
or post-processes a library JAR to manufacture equivalence. Reusing upstream test
evidence for a later receiver build requires identical complete SHA-256 values for
all three Jackson JARs; similar class names or matching version strings do not
satisfy that gate.

## Reproduce and verify

Follow README.md for the source build, compiler JMOD and full upstream runner.
Use new output directories so earlier successes and failures remain available.
The runtime emits `SOURCE_RECEIPT.json`, `SOURCE_BUILD.json` and `RESULT.json`.
The compiler emits `COMPILER_RECEIPT.json`, including the runtime receipt hash,
all four runtime JAR hashes, actual compiler source hashes and host/image counts.
Both build tools record the integration/test source hashes and their own identity.

After all three proof lanes complete, verify their relationship:

```sh
python3 m3/tooling/module-packs/jackson/verify-qualification.py \
  --runtime /new/jackson-proof \
  --compiler /new/jackson-compiler-proof \
  --upstream /new/jackson-upstream-proof \
  --repo-root /path/to/M3jdk21 \
  --out /new/jackson-qualification.json
```

The existing shared receiver workflow executes these same entry points from its
selected PR head, runs all eight upstream/generated lanes, and uploads the exact
source revision and receipts. A queued job, an empty report or a failed process
does not count as a successful hosted gate. PR status must be read separately from
the local evidence described here.

## Remaining product-wide gates

The compiler harness patches the four actual M3 compiler owners into the selected
JDK for qualification. The compiler JMOD contains the bridge; production use
requires the corresponding qualified M3JDK compiler API.

Run the runtime pack with a qualified M3JDK and `--require-m3-runtime` to require
host and linked-image contracts with `UseM3StringStorage` enabled and disabled.
A stock JDK records that lane as unavailable. The existing package-private
`java.lang.M3String` remains the storage owner behind `java.lang.String`.

Full OpenJDK/jtreg coverage, M3VM GC/native-memory/ABI qualification and CPU/heap
measurements remain independent. This implementation makes no throughput,
allocation-reduction, native-file/search-plane or full-family-completion claim.

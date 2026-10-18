# Jackson ObjectMapper in an M3 JMOD pack

The pack builds the existing Jackson 2.19.2 source modules and the small
`jdk.m3.jackson` integration module, then runs the same executable contracts on
the selected JDK and a linked image. Upstream `com.fasterxml.jackson.*` packages,
ObjectMapper behavior, service declarations and module names remain available.

## Source and module ownership

The runtime inputs are the existing in-repository source inclusions of Jackson
annotations/core/databind 2.19.2 and FastDoubleParser 1.0.90. `SOURCE_LOCK.json`
pins their complete source/build/legal trees. Their exact Maven POMs perform the
source builds, FastDoubleParser shading and multi-release compilation in fresh
staging directories. No upstream source or POM is patched by this build.

The existing `m3/tooling/jmod-recipes/tools/jmod_pack.py` owns JMOD assembly and
release-21 flattening. Its existing module graph checks the selected closure and
split packages. The linked image explicitly includes `java.se`, retaining the
JDK modules that Jackson's descriptor marks as optional. Four JMODs are produced:

- `com.fasterxml.jackson.annotation.jmod`
- `com.fasterxml.jackson.core.jmod`
- `com.fasterxml.jackson.databind.jmod`
- `jdk.m3.jackson.jmod`

FastDoubleParser is built from source and shaded by Jackson core's original
build configuration; it is not installed as a competing runtime module.

The build passes the existing Maven `project.build.outputTimestamp` property as
`2026-01-01T00:00:00Z` to the pinned archive plugins. This fixes ZIP entry dates
without editing any donor POM or source, so separate receiving builds can compare
the complete Jackson JAR bytes and reuse exact-artifact upstream test evidence.

## Build and execute

Use a complete JDK 21, Maven 3.9 or later, Python 3.10 or later, and the repository
checkout with all four source directories materialized. A partial clone must
materialize the needed donor trees before an offline build.

First populate the Maven build-plugin cache if necessary:

```sh
python3 m3/tooling/module-packs/jackson/build.py \
  --jdk-home /path/to/jdk21 --maven /path/to/maven/bin/mvn \
  --maven-repo /path/to/maven-cache --resolve-build-tools \
  --out /new/jackson-proof
```

The default is offline. Repeat with a new output directory and the same Maven
cache, omitting `--resolve-build-tools`, to prove that dependency resolution no
longer needs the network. Runtime libraries are source-built in either mode;
network access in the first mode is for Maven build plugins and their tooling
dependencies. `--maven-settings` and `--maven-global-settings` can select explicit
task settings without changing global Maven configuration.

The standalone Maven entry point is:

```sh
mvn -B -ntp -f m3/tooling/module-packs/jackson/pom.xml verify \
  -Dm3.jackson.out=/new/jackson-proof \
  -Dm3.jackson.maven.repo=/path/to/maven-cache
```

The output directory must be absent. Existing proof directories are retained
and refused; no host JDK installation or user file is overwritten. The pack is
an optional separately licensed module and does not advance the JDK-wide
String → arrays → collections → AST/compiler receiving sequence.

## Executed contracts and remaining runtime gates

`JacksonContract`, `M3FileContract` and `JacksonStreamingContract` run first from named source-built modules,
then from the linked image. Test classes are patched into `jdk.m3.jackson` only
for execution. Their private fixture POJOs use an explicit test-only qualified
opening to `com.fasterxml.jackson.databind`. An application's own named model
module must similarly export public members or open its model package for
Jackson reflection; this pack does not bypass JPMS access rules.

`RESULT.json`, `SOURCE_RECEIPT.json`, `SOURCE_BUILD.json`, the existing assembler
receipts and command logs identify the exact inputs and executed lanes. A stock
JDK run records the M3String storage flag as unavailable. A selected M3 runtime
that accepts `-XX:+UseM3StringStorage` additionally runs host/image contracts with
the flag enabled and disabled. `--require-m3-runtime` refuses completion without
that separate lane. The complete upstream fork suites have their own reproducible
qualification entry point below. Full JDK/jtreg coverage, all platforms, and
performance measurements remain separate gates.

## Optional M3AST compiler module

`jdk.m3.jackson.compiler` supplies the integration with the existing public
`com.sun.source.util.M3AST` compiler API. Its dependency on `jdk.compiler` stays
in that separate optional module. The four-module runtime pack above can be used
without the compiler bridge.

After the core build succeeds, the compiler qualification entry point is:

```sh
python3 m3/tooling/module-packs/jackson/compiler-build.py \
  --jdk-home /path/to/jdk21 --repo-root /path/to/M3jdk21 \
  --jars /new/jackson-proof/jars --out /new/jackson-compiler-proof
```

The harness compiles the actual four-source M3AST/compiler owner closure from
the selected repository and records every source hash. It compiles the bridge
against that API, packages a fifth JMOD with the existing assembler, and exercises
real compiler callbacks on the host and the five-module linked image. The
compiler patch is supplied explicitly through `--patch-module jdk.compiler`
during qualification and remains outside the bridge JMOD. Its original source
headers and GPLv2-with-Classpath-Exception ownership remain intact.

This bounded compiler harness is distinct from a full M3JDK VM build. Use a
qualified M3JDK compiler for production; the harness records its limited scope
in `COMPILER_RECEIPT.json` and does not establish the M3String VM storage lane.
The fifth-module pack reuses the complete legal bundle from the core build.

## Indexed input, output and prepared sequences

`M3ObjectMapper` inherits the full upstream ObjectMapper API and adds M3File and
NIO Path boundaries. `M3File.read(Reader, limit)` admits decoded text into indexed
String chunks without taking ownership of the supplied reader. The Path factory
requires an explicit charset. Mapper Path overloads retain Jackson's byte encoding
and BOM detection. `writeValueAsM3File` streams JSON into bounded chunks; it does
not first construct a complete document String.

```java
M3ObjectMapper mapper = new M3ObjectMapper();
var binding = mapper.prepare(Person.class);
M3File document = binding.writeValueAsM3File(new Person("Hitesh", 31), 1_000_000);
Person person = binding.readValue(document);

M3File array = binding.writeValuesAsM3File(people, true, 1_000_000);
try (var values = binding.readValues(array)) {
    while (values.hasNextValue()) consume(values.nextValue());
}
```

The prepared reader and writer preserve the full resolved generic type and the
configured Jackson serializers, annotations, modules, filters and root separator.
Sequences use Jackson's MappingIterator and SequenceWriter. Close a reader iterator
when stopping early or handling malformed data. Details and complete imports are
in `INTEGRATION.md`.

The M3AST bridge also accepts `TypeReference<T>` and `JavaType`. Its default
projection retains the existing top-level declaration contract; the explicit
`allDeclarations` consumer adds named member declarations. All retained metadata
is detached from the compiler session.

## Full upstream fork qualification

`upstream-qualification.py` executes the original tests from the pinned user forks,
then runs the same selected cases against the exact three generated Jackson JARs.
It verifies nonempty test reports, exact testcase selection, the known upstream
skips, and actual loaded class origins. See `UPSTREAM_QUALIFICATION.md` for the
command, fork revisions, JVM prerequisites, environment adjustments and scope.

## Jackson fork workspace

The generated development receiver in `hsoliwal/jackson-databind` lives at
`m3/m3jdk-jackson`. Its sources and tests are the same optional module pack.
The original upstream source, tests and POM remain the pinned test oracle. From
that checkout, provide the M3JDK source repository explicitly:

```sh
python3 m3/m3jdk-jackson/build.py \
  --repo-root /path/to/M3jdk21 --jdk-home /path/to/jdk21 \
  --maven /path/to/maven/bin/mvn --maven-repo /path/to/maven-cache \
  --out /new/fork-jackson-proof
python3 m3/m3jdk-jackson/compiler-build.py \
  --repo-root /path/to/M3jdk21 --jdk-home /path/to/jdk21 \
  --jars /new/fork-jackson-proof/jars --out /new/fork-compiler-proof
```

For the standalone Maven entry point in the fork, pass
`-Dm3.jackson.repo.root=/path/to/M3jdk21` and an absent `m3.jackson.out` directory.
The source lock still verifies the M3JDK donor trees and existing assembler owners.

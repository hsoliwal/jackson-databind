<!-- Copyright 2026 Hitesh Soliwal and contributors. SPDX-License-Identifier: Apache-2.0 -->
# Jackson with the M3 file, String and compiler owners

This receiver packages the full existing Jackson 2.19.2 ObjectMapper implementation
and adds two optional integration modules. Jackson's annotation, core and databind
module names and public APIs stay available. Runtime code has no Synexia dependency.

| Module | Responsibility |
| --- | --- |
| `com.fasterxml.jackson.annotation` | Existing upstream Jackson annotations |
| `com.fasterxml.jackson.core` | Existing streaming parser/generator and shaded FastDoubleParser |
| `com.fasterxml.jackson.databind` | Full existing ObjectMapper, JsonNode, readers, writers and binding machinery |
| `jdk.m3.jackson` | Indexed `M3File` input/output, `M3ObjectMapper`, prepared single-value and sequence `M3Binding` |
| `jdk.m3.jackson.compiler` | Optional detached declaration projection from the actual `com.sun.source.util.M3AST` session |

The compiler module is optional; normal object mapping does not require javac.
All JMODs are built using the existing `jmod_pack.py` owner. The standard module
graph and `java.se` closure preserve Jackson's optional desktop, SQL and XML support.

## M3 String ownership

M3JDK's `java.lang.M3String` is package-private and remains behind the public
`java.lang.String` API. This receiver consumes that API. It does not add another
string interner, native dictionary, local byte-backed arena, JNI shadow owner or
replacement String implementation. Whether the selected VM actually uses M3 String
storage is independently tested through its `UseM3StringStorage` flag.

Jackson's ordinary buffers and explicit output Strings remain its public compatibility
and parsing boundaries. This work does not claim to remove every Jackson allocation
or turn its complete mutable tree model into an immutable indexed tree. The complete
upstream parser, POJO binder and serializer retain their existing contracts.

## Indexed M3File input and output

`M3File` represents immutable decoded content, rather than a filesystem path. Its
donor is Synexia's `MIndexFile` occurrence/UTF-16 layout and its exact adaptive
`MIndexFileTokenStore`. It retains existing String references, one reference per
distinct spelling in this file, and primitive occurrence handles plus prefix offsets.
The temporary admission map is discarded. It never retains an independently copied
character or byte spelling array. Empty input pieces remain indexed occurrences.

This is a decoded indexed I/O port. It does not claim to transplant the donor's
posting indexes, rolling searches, persistence, raw-byte source facts, native
admission or full MIndexFile public API. The bounded target namespace is
`jdk.m3.jackson.M3File`; the broader reserved M3File family is still separately
qualified in the canonical receiving plan.

```java
import jdk.m3.jackson.M3File;
import jdk.m3.jackson.M3ObjectMapper;

public record Person(String name, int age) { }

M3ObjectMapper mapper = new M3ObjectMapper();
M3File input = M3File.of("{\"name\":", "\"Hitesh\"", ",\"age\":31}");
Person person = mapper.readValue(input, Person.class);
```

Factories snapshot the sequence of String references and deduplicate equal atom
references locally. `slice` and `subSequence` share the original source index and
retain only UTF-16 coordinates. Slices may split surrogate pairs, as String does.
The `source*` metadata methods always refer to the retained original index, so the
metadata is unambiguous on a slice.

The Reader traverses occurrence handles forward and bulk-copies from existing
Strings into Jackson's requested buffer. Its forward traversal is linear in code
units and crossed occurrences after initial positioning. It supports offset-only
mark/reset, standard EOF and zero-length reads, bounded forward skip, and idempotent
close. Closing a Reader releases that Reader's reference; it does not close the
immutable M3File or other readers. `toString()` explicitly materializes an uncached
output when a caller requests one. The mapper never calls it for indexed input.

Null atom arrays, lists or elements are rejected. Total logical UTF-16 length is
limited to `Integer.MAX_VALUE`, matching CharSequence and Jackson Reader geometry.
The implementation rejects overflow before building its index. Large physical files
can instead use the streaming Path APIs; this receiver does not automatically
index an unbounded physical file in memory.

`M3File.read(Reader, maxUtf16Length)` reads bounded decoded content into frozen
String chunks. It leaves the borrowed Reader open, including when admission fails.
`M3File.read(Path, Charset, maxUtf16Length)` opens and closes its own reader through
the explicitly selected decoder. Both enforce a UTF-16 length limit.

`M3File.newWriter(limit)` is a caller-owned Writer for indexed output. Writes use a
temporary chunk buffer; `finish()` freezes the remaining text, releases the buffer,
and returns an immutable indexed file. Repeated `finish()` calls return the same
file. `close()` finishes the writer; writes after finish/close fail. An operation
that exceeds the limit is rejected before any of that operation is appended.
`M3File.writeTo(Writer)` transfers a slice directly from its retained atoms without
closing or flushing the destination. `append(CharSequence)` does not call a
sequence's `toString()` to flatten the input.

```java
M3File output = mapper.writeValueAsM3File(person, 1_000_000);
try (var destination = java.nio.file.Files.newBufferedWriter(Path.of("person.json"))) {
    output.writeTo(destination);
}
```

The output's atom boundaries are implementation chunks, not a lexical tokenization
of JSON. Freezing retains ordinary immutable Strings and the existing adaptive
index. Jackson still controls escaping, number formatting and serialization.

## File-system and generic-type boundaries

Path overloads use `Files.newInputStream` and `Files.newOutputStream`. Jackson
retains byte encoding and BOM detection; a Path is not silently decoded as UTF-8.
The Path provider owns access and filesystem exceptions. Writes use normal
create/truncate semantics. The mapper closes streams it opens even if the caller
disabled Jackson's automatic close features. Existing inherited Reader and stream
overloads retain upstream ownership semantics.

```java
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.Path;
import java.util.List;

List<Person> people = mapper.readValue(
    Path.of("people.json"), new TypeReference<List<Person>>() { });
mapper.writeValue(Path.of("people-out.json"), people);

var binding = mapper.prepare(new TypeReference<List<Person>>() { });
List<Person> same = binding.readValue(M3File.of("[", "{\"name\":\"Hitesh\",\"age\":31}", "]"));
String exported = binding.writeValueAsString(same);
```

`M3Binding` reuses Jackson's existing immutable ObjectReader/ObjectWriter preparation.
The caller owns the plan and its lifetime. There is no new global cache or binding
engine. The reader and writer preserve their immutable databind configuration, but
retain the mapper's shared `JsonFactory` and other external collaborators under
Jackson's normal contract. Finish configuring the mapper and factory before
preparation and do not mutate shared collaborators while using the binding. Custom serializers
and external collaborators keep Jackson's existing thread-safety obligations.

`M3ObjectMapper.copy()` and `copyWith(JsonFactory)` preserve the subclass and
Jackson's isolated configuration-copy contract. Inherited ObjectMapper methods and
existing package names remain available. No global default mapper is reconfigured.

## Streaming sequences

`M3Binding<T>.readValues(M3File)` and `readValues(Path)` return Jackson's actual
MappingIterator with an owned boundary. They accept both array-wrapped sequences
and whitespace-separated roots with the configured ObjectReader semantics. Mapper
convenience overloads accept Class, TypeReference and JavaType directly. The String
overload retains ObjectReader's existing String behavior.

```java
var personBinding = mapper.prepare(Person.class);
M3File batch = personBinding.writeValuesAsM3File(
    List.of(new Person("Hitesh", 31), new Person("Sam", 27)), true, 1_000_000);
try (var values = personBinding.readValues(batch)) {
    while (values.hasNextValue()) {
        Person next = values.nextValue();
        consume(next);
    }
}
personBinding.writeValuesAsArray(Path.of("people.json"), people);
personBinding.writeValues(Path.of("people-sequence.json"), people);
```

The binding delegates sequence iteration and serialization to Jackson. Root
separators, generic element types, null coercion, configured modules and recovery
after binding errors retain the upstream behavior. In particular, null acceptance
depends on the selected deserializer; a root null is not converted into a record
value by this adapter.

Exhausting or explicitly closing an owned iterator releases the parser and input,
including when AUTO_CLOSE_SOURCE is disabled. Use try-with-resources when stopping
early or after malformed input. Sequence output owns its stream and generator,
including failures during initialization or close. Disabling AUTO_CLOSE_TARGET
does not leak a stream opened by a Path overload. Iterable failures propagate and
the normal Jackson exception remains primary when cleanup also fails.

## M3AST precompute boundary

The existing M3AST API is a live Java compiler session, not a JSON DOM. The optional
compiler bridge registers an `M3AST.Consumer`, obtains attributed type declarations,
and calls `Unit.project` with one stable projection identity. Multiple consumers of
that same event share the compiler-owned computed result. Reuse never spans tasks,
source generations or classpaths automatically.

The detached `TypeShape` contains the qualified Java name, declaration kind,
source URI, exact UTF-16 source digest, declared-field metadata and ordered record
components. It retains no compiler task, tree, Unit, Element or TypeMirror. A sink
decides how much detached metadata to retain. Jackson can serialize and restore
the immutable projection with its normal record binding.

```java
import com.sun.source.util.JavacTask;
import com.sun.source.util.M3AST;
import jdk.m3.jackson.compiler.M3AstBindings;

JavacTask task = /* the application's existing compiler task */;
try (M3AST session = M3AST.open(task)) {
    session.addConsumer(new M3AstBindings(shape -> {
        // Keep only detached facts; do not retain live compiler objects.
        consume(shape);
    }));
    task.call();
}
```

`M3AstBindings.prepare(shape, runtimeClass, mapper)` checks the declaration name,
kind, and record component names, order and declared generic types before requesting
Jackson's actual runtime binding. The TypeReference and JavaType overloads preserve
the complete resolved root type, such as `Box<Person>`, in the prepared binding.
Record signatures are compared at declaration level, where a declared `T` stays
`T`; the comparison does not erase the binding to its raw class. Nested owners,
arrays, wildcards and literal dollar-sign identifiers keep their Java type identity.
Those checks catch mismatched inputs; they do not prove the Class was compiled
from that source. No class is dynamically loaded based on an untrusted JSON name.
Source metadata never replaces Jackson's actual annotation, mixin, visibility,
module, generic type or serializer decisions. A source digest is not a bytecode or
classpath equivalence key.

The default `projection()` identity and callback behavior are unchanged. Use
`M3AstBindings.allDeclarations(sink)` to include named member-nested declarations,
or reuse `allDeclarationsProjection()` to share that computation within one event.
Local and anonymous classes have no stable canonical name for this API and are
excluded. Unresolved compiler types fail closed. Every result is immutable and
contains no live Unit, Tree, Element, TypeMirror or JavacTask. No source type string
is parsed into a second reflection or type-resolution engine.

To build and exercise the optional compiler JMOD after the runtime pack:

```sh
python3 m3/tooling/module-packs/jackson/compiler-build.py \
  --jdk-home /path/to/jdk21 --repo-root . \
  --jars /new/jackson-proof/jars --out /new/jackson-compiler-proof
```

This qualification harness compiles the actual four existing M3 compiler source
owners into an isolated `jdk.compiler` patch, compiles the optional module against
them, packages five named JMODs, and runs real compiler callback tests on the host
and linked image. It uses no substitute M3AST implementation. The patch is an
explicit qualification mechanism; production should use the corresponding
qualified M3JDK compiler. The JMOD does not embed or relicense the compiler patch.

## JPMS and licenses

An application module normally declares `requires jdk.m3.jackson`. Its model
package must export public members or open reflective model access to
`com.fasterxml.jackson.databind`, for example:

```java
module my.application {
    requires jdk.m3.jackson;
    opens my.application.model to com.fasterxml.jackson.databind;
}
```

The runtime integration module does not broadly open application packages. The
test-only openings used by contract fixtures do not become production flags.

The optional integration and canonical recipe source remain Apache-2.0 with
Hitesh Soliwal and contributor attribution. Jackson and FastDoubleParser retain
their exact original source/legal notices and artifact provenance. Existing
OpenJDK compiler sources retain their GPLv2 plus Classpath Exception headers.
Recipe generation does not change donor licensing.

## Evidence and limits

The current canonical recipe is `com.synexia.rewrite.M3JacksonJmodFullRecipe`, in Synexia.
It composes the existing Java snapshot, text snapshot and module descriptor owners.
Its content-addressed packet records target/source revisions, exact preimages,
absent-before additions, postimages and provenance. Tests exercise real SDK output,
fixed point, drift/occupied-addition refusal, ordering and independent-worker replay.

The runtime contract suite compares the real Jackson implementation across indexed
token seams, duplicate fields, object ordering, arbitrary-sized/decimal numbers,
Unicode, malformed inputs, records, annotations, generic types, custom modules,
configuration copies, prepared-plan concurrency and physical-file encodings. The
file suite separately exercises metadata geometry and Reader lifetime boundaries.
The streaming suite adds bounded ingestion/output, sequence parity and owned
resource failure paths. The compiler suite exercises real M3AST callbacks,
memoization, generic bindings, named nested declarations, independent sessions and
detached metadata round trips. The full upstream tests are independently replayed
from the pinned forks against the generated Jackson JARs, with equal testcase
selection and verified loaded class origins. Exact executed results belong in
`QUALIFICATION.md`, `UPSTREAM_QUALIFICATION.md` and build receipts.

A successful stock Java 21 source/JMOD/compiler-patch run does not establish a full
M3 VM build, flag-on/off native String compatibility, all operating systems,
jtreg coverage or a performance gain. Those gates
remain explicitly recorded. This optional module does not advance the global
String → arrays → collections → AST/compiler receiving sequence.

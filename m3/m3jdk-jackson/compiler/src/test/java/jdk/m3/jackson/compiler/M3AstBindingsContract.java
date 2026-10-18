/* Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package jdk.m3.jackson.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.source.util.JavacTask;
import com.sun.source.util.M3AST;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import jdk.m3.jackson.M3File;
import jdk.m3.jackson.M3Binding;
import jdk.m3.jackson.M3ObjectMapper;
import jdk.m3.jackson.compiler.fixture.AstModels;
import jdk.m3.jackson.compiler.fixture.AstRecord;

/** Uses the actual patched M3 javac session, with no substitute API implementation. */
public final class M3AstBindingsContract {
    private static int checks;
    private M3AstBindingsContract() { }
    public static void main(String[] args) throws Exception {
        String code = "package jdk.m3.jackson.compiler.fixture; "
            + "public record AstRecord(String name, int count) { "
            + "static final String DECOY = \"class Fake { int ignored; }\"; }";
        Path output = Files.createTempDirectory("m3-jackson-ast-");
        List<M3AstBindings.TypeShape> shapes = new ArrayList<>();
        List<M3AstBindings.TypeShape> shared = new ArrayList<>();
        M3AST.Summary summary;
        try {
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new AssertionError("real javac required");
            try (var files = compiler.getStandardFileManager(null, null, null)) {
                var input = new SimpleJavaFileObject(
                    URI.create("string:///jdk/m3/jackson/compiler/fixture/AstRecord.java"),
                    javax.tools.JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreErrors) { return code; }
                };
                JavacTask task = (JavacTask) compiler.getTask(null, files, null,
                    List.of("-proc:none", "-d", output.toString()), null, List.of(input));
                try (M3AST session = M3AST.open(task)) {
                    session.addConsumer(new M3AstBindings(shapes::add));
                    session.addConsumer(new M3AST.Consumer() {
                        @Override public void analyzed(M3AST.Unit unit) throws Exception {
                            shared.add(unit.project(M3AstBindings.projection()));
                        }
                    });
                    if (!task.call()) throw new AssertionError("real compiler failure");
                    summary = session.summary();
                }
            }
            if (shapes.isEmpty() || shapes.size() != shared.size()) throw new AssertionError("no AST events");
            for (int i = 0; i < shapes.size(); i++) {
                if (shapes.get(i) != shared.get(i)) throw new AssertionError("projection was recomputed");
            }
            if (!summary.completed() || !summary.closed() || summary.consumerFailed()
                    || summary.compilerErrors() != 0 || summary.projectionHits() != shapes.size()
                    || summary.projectionComputations() != shapes.size()
                    || summary.capturedSources() != 1) {
                throw new AssertionError("incorrect compiler ownership counters: " + summary);
            }
            var mapper = new M3ObjectMapper();
            for (var shape : shapes) {
                String json = mapper.writeValueAsString(shape);
                equal(shape, mapper.readValue(M3File.of(json), M3AstBindings.TypeShape.class));
                if (shape.qualifiedName().contains("Fake")) throw new AssertionError("code-looking string parsed");
            }
            var shape = shapes.getFirst();
            equal(AstRecord.class.getCanonicalName(), shape.qualifiedName());
            equal(List.of("name", "count"), shape.recordComponents().stream()
                .map(M3AstBindings.Member::name).toList());
            var binding = M3AstBindings.prepare(shape, AstRecord.class, mapper);
            AstRecord item = new AstRecord("हिन्दी", 7);
            equal(item, binding.readValue(M3File.of(binding.writeValueAsString(item))));
            try {
                M3AstBindings.prepare(shape, String.class, mapper);
                throw new AssertionError("mismatched runtime type admitted");
            } catch (IllegalArgumentException expected) { }
            extended();
            System.out.println("M3_AST_BINDINGS_PASS shapes=" + shapes.size()
                + " projections=" + summary.projectionComputations() + " hits=" + summary.projectionHits());
            System.out.println("M3_AST_BINDINGS_EXTENDED_PASS checks=" + checks);
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static final String MODELS_SOURCE = """
        package jdk.m3.jackson.compiler.fixture;
        import com.fasterxml.jackson.annotation.JsonCreator;
        import com.fasterxml.jackson.annotation.JsonProperty;
        import java.lang.annotation.*;
        import java.util.*;
        public final class AstModels {
            static final String DECOY = "record NotADeclaration(int ignored) {}";
            public record Person(@JsonProperty("full_name") String name, int count) {}
            public record Box<T>(T value, List<T> items, Map<String,T> named) {}
            public record ArraysBox<T>(T[] items, T[][] grid) {}
            public record Wild(List<? extends Number> upper, List<? super Integer> lower, List<?> any) {}
            public static class Owner<T> {
                public class Child<U> { public T outer; public U inner; }
            }
            public record Owned(Owner<String>.Child<Integer> item) {}
            public static final class GenericBean<T> {
                private final T value;
                @JsonCreator private GenericBean(@JsonProperty("value") T value) { this.value = value; }
                public T getValue() { return value; }
            }
            public interface MarkerInterface {}
            public enum Choice { ONE, TWO }
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)
            public @interface TypeMark {}
            public record Annotated(@TypeMark String value, List<@TypeMark String> names) {}
            public record Dollar$Record(String value) {}
            public record Bounded<T extends Number & Comparable<T>>(T value) {}
            public static final class Deep { public record Leaf(String value) {} }
            public static Object local() { class Local {} return new Local(); }
            public static Object anonymous() { return new Object() {}; }
        }
        """;

    private record Captured(List<M3AstBindings.TypeShape> declarations,
                            List<M3AstBindings.TypeShape> topLevels, M3AST.Summary summary) { }

    private static void extended() throws Exception {
        Captured first = captureAll(MODELS_SOURCE);
        Captured second = captureAll(MODELS_SOURCE);
        equal(first.declarations(), second.declarations());
        truth(first.declarations().getFirst() != second.declarations().getFirst(),
              "independent compiler sessions shared a projection object");
        equal(1, first.topLevels().size());
        equal(AstModels.class.getCanonicalName(), first.topLevels().getFirst().qualifiedName());
        truth(first.topLevels().getFirst() == first.declarations().getFirst(),
              "all-declaration projection did not reuse the event's top-level shape");
        equal(List.of("AstModels", "AstModels.Person", "AstModels.Box", "AstModels.ArraysBox",
            "AstModels.Wild", "AstModels.Owner", "AstModels.Owner.Child", "AstModels.Owned",
            "AstModels.GenericBean", "AstModels.MarkerInterface", "AstModels.Choice",
            "AstModels.TypeMark", "AstModels.Annotated", "AstModels.Dollar$Record",
            "AstModels.Bounded", "AstModels.Deep", "AstModels.Deep.Leaf"),
            first.declarations().stream().map(shape -> shape.qualifiedName()
                .substring("jdk.m3.jackson.compiler.fixture.".length())).toList());
        for (var shape : first.declarations()) {
            truth(!shape.qualifiedName().contains("NotADeclaration")
                    && !shape.qualifiedName().endsWith(".Local"),
                    "local/anonymous/code-looking content entered the declaration projection");
        }
        Captured changed = captureAll(MODELS_SOURCE + "\n// independent source generation\n");
        truth(!first.declarations().getFirst().sourceUtf16Sha256().equals(
            changed.declarations().getFirst().sourceUtf16Sha256()), "changed source reused the seal");

        M3ObjectMapper mapper = new M3ObjectMapper();
        ObjectMapper oracle = new ObjectMapper();
        for (var shape : first.declarations()) {
            equal(shape, mapper.readValue(M3File.of(mapper.writeValueAsString(shape)),
                                          M3AstBindings.TypeShape.class));
        }
        var personShape = shape(first, AstModels.Person.class);
        truth(personShape.declaredFields().stream().filter(member -> member.name().equals("name"))
            .anyMatch(member -> member.annotations().contains("com.fasterxml.jackson.annotation.JsonProperty")),
            "attributed declaration annotation was lost");
        AstModels.Person person = new AstModels.Person("हिन्दी 😀", 7);
        var personBinding = M3AstBindings.prepare(personShape, AstModels.Person.class, mapper);
        String json = personBinding.writeValueAsString(person);
        equal(oracle.writeValueAsString(person), json);
        truth(json.contains("\"full_name\""), "runtime Jackson annotation did not control binding");
        equal(person, personBinding.readValue(M3File.of(json)));

        var boxShape = shape(first, AstModels.Box.class);
        TypeReference<AstModels.Box<AstModels.Person>> boxType = new TypeReference<>() { };
        var box = new AstModels.Box<>(person, List.of(person), Map.of("one", person));
        M3Binding<AstModels.Box<AstModels.Person>> boxBinding =
            M3AstBindings.prepare(boxShape, boxType, mapper);
        equal(mapper.getTypeFactory().constructType(boxType), boxBinding.type());
        equal(box, boxBinding.readValue(M3File.of(oracle.writeValueAsString(box))));
        JavaType resolved = mapper.getTypeFactory().constructType(boxType);
        M3Binding<AstModels.Box<AstModels.Person>> resolvedBinding =
            M3AstBindings.prepare(boxShape, resolved, mapper);
        truth(resolvedBinding.type() == resolved, "resolved JavaType was replaced by a raw type");
        equal(box, resolvedBinding.readValue(boxBinding.writeValueAsString(box)));
        TypeReference<AstModels.Box<List<AstModels.Person>>> nestedType = new TypeReference<>() { };
        var nested = new AstModels.Box<>(List.of(person), List.of(List.of(person)),
                                         Map.of("one", List.of(person)));
        var nestedBinding = M3AstBindings.prepare(boxShape, nestedType, mapper);
        equal(nested, nestedBinding.readValue(nestedBinding.writeValueAsString(nested)));

        TypeReference<AstModels.GenericBean<AstModels.Person>> beanType = new TypeReference<>() { };
        var beanBinding = M3AstBindings.prepare(shape(first, AstModels.GenericBean.class), beanType, mapper);
        String beanJson = "{\"value\":" + json + "}";
        AstModels.GenericBean<AstModels.Person> bean = beanBinding.readValue(M3File.of(beanJson));
        equal(person, bean.getValue());
        equal(oracle.readValue(beanJson, beanType), bean);
        equal(oracle.writeValueAsString(bean), beanBinding.writeValueAsString(bean));

        TypeReference<AstModels.ArraysBox<AstModels.Person>> arrayType = new TypeReference<>() { };
        var arrayBinding = M3AstBindings.prepare(shape(first, AstModels.ArraysBox.class), arrayType, mapper);
        var arrayValue = new AstModels.ArraysBox<>(new AstModels.Person[] {person},
                                                   new AstModels.Person[][] {{person}});
        var restoredArray = arrayBinding.readValue(arrayBinding.writeValueAsString(arrayValue));
        equal(AstModels.Person[].class, restoredArray.items().getClass());
        truth(Arrays.equals(arrayValue.items(), restoredArray.items()), "generic array binding changed");
        truth(Arrays.deepEquals(arrayValue.grid(), restoredArray.grid()), "generic array rank changed");

        var wildShape = shape(first, AstModels.Wild.class);
        var wildBinding = M3AstBindings.prepare(wildShape, AstModels.Wild.class, mapper);
        var wild = new AstModels.Wild(List.of(1, 2), List.of(3), List.of("any"));
        equal(wild, wildBinding.readValue(wildBinding.writeValueAsString(wild)));
        equal(AstModels.Owned.class,
            M3AstBindings.prepare(shape(first, AstModels.Owned.class), AstModels.Owned.class, mapper)
                .type().getRawClass());
        var annotatedShape = shape(first, AstModels.Annotated.class);
        equal(List.of("java.lang.String", "java.util.List<java.lang.String>"),
            annotatedShape.recordComponents().stream().map(M3AstBindings.Member::javaType).toList());
        var annotatedBinding = M3AstBindings.prepare(annotatedShape, AstModels.Annotated.class, mapper);
        var annotated = new AstModels.Annotated("v", List.of("one"));
        equal(annotated, annotatedBinding.readValue(annotatedBinding.writeValueAsString(annotated)));
        var bounded = M3AstBindings.prepare(shape(first, AstModels.Bounded.class),
            new TypeReference<AstModels.Bounded<Integer>>() { }, mapper);
        equal(new AstModels.Bounded<>(17), bounded.readValue("{\"value\":17}"));
        var dollar = M3AstBindings.prepare(shape(first, AstModels.Dollar$Record.class),
            AstModels.Dollar$Record.class, mapper);
        equal(new AstModels.Dollar$Record("x"), dollar.readValue("{\"value\":\"x\"}"));
        var leaf = M3AstBindings.prepare(shape(first, AstModels.Deep.Leaf.class),
            AstModels.Deep.Leaf.class, mapper);
        equal(new AstModels.Deep.Leaf("x"), leaf.readValue("{\"value\":\"x\"}"));
        equal(AstModels.Choice.ONE, M3AstBindings.prepare(shape(first, AstModels.Choice.class),
            AstModels.Choice.class, mapper).readValue("\"ONE\""));
        equal(AstModels.MarkerInterface.class, M3AstBindings.prepare(
            shape(first, AstModels.MarkerInterface.class), AstModels.MarkerInterface.class, mapper)
            .type().getRawClass());
        equal(AstModels.TypeMark.class, M3AstBindings.prepare(shape(first, AstModels.TypeMark.class),
            AstModels.TypeMark.class, mapper).type().getRawClass());

        reject(() -> M3AstBindings.prepare(withKind(personShape, "CLASS"), AstModels.Person.class, mapper));
        reject(() -> M3AstBindings.prepare(withKind(shape(first, AstModels.Choice.class), "INTERFACE"),
                                           AstModels.Choice.class, mapper));
        reject(() -> M3AstBindings.prepare(withKind(shape(first, AstModels.MarkerInterface.class), "CLASS"),
                                           AstModels.MarkerInterface.class, mapper));
        reject(() -> M3AstBindings.prepare(withKind(shape(first, AstModels.TypeMark.class), "INTERFACE"),
                                           AstModels.TypeMark.class, mapper));
        reject(() -> M3AstBindings.prepare(withComponents(personShape, List.of()),
                                           AstModels.Person.class, mapper));
        reject(() -> M3AstBindings.prepare(withComponents(personShape, personShape.recordComponents().reversed()),
                                           AstModels.Person.class, mapper));
        reject(() -> M3AstBindings.prepare(withType(personShape, "count", "long"),
                                           AstModels.Person.class, mapper));
        reject(() -> M3AstBindings.prepare(withType(boxShape, "value", "java.lang.Object"), boxType, mapper));
        reject(() -> M3AstBindings.prepare(withType(boxShape, "value", "U"), boxType, mapper));
        reject(() -> M3AstBindings.prepare(withType(boxShape, "items", "java.util.List<java.lang.String>"),
                                           boxType, mapper));
        reject(() -> M3AstBindings.prepare(withType(shape(first, AstModels.ArraysBox.class), "items", "T[][]"),
                                           arrayType, mapper));
        reject(() -> M3AstBindings.prepare(withType(wildShape, "lower", "java.util.List<? extends java.lang.Integer>"),
                                           AstModels.Wild.class, mapper));
        var ownedShape = shape(first, AstModels.Owned.class);
        String ownedType = ownedShape.recordComponents().getFirst().javaType();
        truth(ownedType.contains("Owner<java.lang.String>.Child<java.lang.Integer>"),
              "generic enclosing owner was erased: " + ownedType);
        reject(() -> M3AstBindings.prepare(withType(ownedShape, "item",
            ownedType.replace("java.lang.String", "java.lang.Integer")), AstModels.Owned.class, mapper));
        reject(() -> M3AstBindings.prepare(personShape, AstModels.local().getClass(), mapper));
        reject(() -> M3AstBindings.prepare(personShape, AstModels.anonymous().getClass(), mapper));
        reject(() -> M3AstBindings.prepare(personShape, AstModels.Person[].class, mapper));
        String formatted = boxShape.recordComponents().get(2).javaType().replace(", ", ",\n\t");
        equal(boxBinding.type(), M3AstBindings.prepare(withType(boxShape, "named", formatted), boxType, mapper).type());
        unresolvedTypeFailsClosed();
    }

    private static Captured captureAll(String source) throws Exception {
        Path output = Files.createTempDirectory("m3-jackson-members-");
        var declarations = new ArrayList<M3AstBindings.TypeShape>();
        var topLevels = new ArrayList<M3AstBindings.TypeShape>();
        var shared = new ArrayList<M3AstBindings.TypeShape>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            List<String> options = compilerOptions(output);
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, options, null,
                List.of(input("AstModels", source)));
            M3AST.Summary summary;
            try (M3AST session = M3AST.open(task)) {
                session.addConsumer(M3AstBindings.allDeclarations(declarations::add));
                session.addConsumer(new M3AstBindings(topLevels::add));
                session.addConsumer(new M3AST.Consumer() {
                    @Override public void analyzed(M3AST.Unit unit) throws Exception {
                        shared.addAll(unit.project(M3AstBindings.allDeclarationsProjection()));
                    }
                });
                truth(task.call(), "actual member compilation failed: " + diagnostics.getDiagnostics());
                summary = session.summary();
            }
            equal(2L, summary.projectionComputations());
            equal(2L, summary.projectionHits());
            equal(1L, summary.capturedSources());
            truth(summary.completed() && summary.closed() && !summary.consumerFailed()
                    && summary.compilerErrors() == 0, "member compiler ownership failed: " + summary);
            equal(declarations.size(), shared.size());
            for (int i = 0; i < declarations.size(); i++) {
                truth(declarations.get(i) == shared.get(i), "member projection was recomputed");
            }
            return new Captured(List.copyOf(declarations), List.copyOf(topLevels), summary);
        } finally {
            delete(output);
        }
    }

    private static void unresolvedTypeFailsClosed() throws Exception {
        Path output = Files.createTempDirectory("m3-jackson-unresolved-");
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var captured = new ArrayList<M3AstBindings.TypeShape>();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, compilerOptions(output),
                null, List.of(input("Broken", "package jdk.m3.jackson.compiler.fixture;"
                    + " public record Broken(DoesNotExist value) {}")));
            try (M3AST session = M3AST.open(task)) {
                session.addConsumer(M3AstBindings.allDeclarations(captured::add));
                boolean refused = false;
                try {
                    task.call();
                } catch (RuntimeException expected) {
                    for (Throwable cause = expected; cause != null; cause = cause.getCause()) {
                        if (cause instanceof IllegalArgumentException
                                && cause.getMessage().contains("unresolved source type")) refused = true;
                    }
                }
                truth(refused, "an unresolved attributed type was silently guessed");
                truth(session.summary().consumerFailed(), "failed source projection was not recorded");
            }
            truth(captured.isEmpty(), "an invalid declaration escaped to a consumer");
            truth(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                  "invalid source produced no actual compiler diagnostic");
        } finally {
            delete(output);
        }
    }

    private static List<String> compilerOptions(Path output) {
        var options = new ArrayList<>(List.of("-proc:none", "-d", output.toString(),
                                             "--add-modules", "com.fasterxml.jackson.annotation"));
        String modulePath = System.getProperty("jdk.module.path");
        if (modulePath != null && !modulePath.isBlank()) {
            options.add("--module-path");
            options.add(modulePath);
        }
        return options;
    }

    private static JavaFileObject input(String name, String code) {
        return new SimpleJavaFileObject(URI.create("string:///jdk/m3/jackson/compiler/fixture/"
            + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreErrors) { return code; }
        };
    }

    private static M3AstBindings.TypeShape shape(Captured captured, Class<?> type) {
        return captured.declarations().stream().filter(shape -> shape.qualifiedName().equals(type.getCanonicalName()))
            .findFirst().orElseThrow(() -> new AssertionError("missing declaration: " + type));
    }

    private static M3AstBindings.TypeShape withKind(M3AstBindings.TypeShape shape, String kind) {
        return new M3AstBindings.TypeShape(shape.qualifiedName(), kind, shape.sourceUri(),
            shape.sourceUtf16Sha256(), shape.declaredFields(), shape.recordComponents());
    }

    private static M3AstBindings.TypeShape withComponents(M3AstBindings.TypeShape shape,
                                                         List<M3AstBindings.Member> components) {
        return new M3AstBindings.TypeShape(shape.qualifiedName(), shape.kind(), shape.sourceUri(),
            shape.sourceUtf16Sha256(), shape.declaredFields(), components);
    }

    private static M3AstBindings.TypeShape withType(M3AstBindings.TypeShape shape, String name, String type) {
        return withComponents(shape, shape.recordComponents().stream().map(member -> member.name().equals(name)
            ? new M3AstBindings.Member(member.name(), type, member.annotations()) : member).toList());
    }

    private static void delete(Path output) throws Exception {
        try (var paths = Files.walk(output)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }

    private static void reject(Action action) throws Exception {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("mismatched source/runtime declaration admitted");
    }

    private static void truth(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}

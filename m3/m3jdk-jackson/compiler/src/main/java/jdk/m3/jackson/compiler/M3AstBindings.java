/* Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package jdk.m3.jackson.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.sun.source.util.M3AST;
import java.io.IOException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import jdk.m3.jackson.M3Binding;
import jdk.m3.jackson.M3ObjectMapper;

/**
 * Detached Jackson-readable declaration metadata from the existing M3AST session.
 * The compiler owns parsing/attribution and memoizes one projection per analyzed
 * event. This class retains no Unit, tree, element, type mirror or compiler task.
 *
 * <p>Declaration facts describe Java source, not Jackson's configurable property
 * model. A prepared runtime binding is always built by the configured mapper
 * against the actual Class; source metadata never overrides annotations, mixins,
 * custom modules, visibility or type resolution. No global class/source cache is
 * introduced, and the source digest is not a classpath or bytecode-equivalence key.
 */
public final class M3AstBindings implements M3AST.Consumer {
    private static final M3AST.Projection<TypeShape> SHAPE =
        new M3AST.Projection<>("jdk.m3.jackson.declaration/1", M3AstBindings::capture);
    private static final M3AST.Projection<List<TypeShape>> ALL_DECLARATIONS =
        new M3AST.Projection<>("jdk.m3.jackson.member-declarations/1", M3AstBindings::captureAll);
    private final Consumer<? super TypeShape> sink;
    private final boolean includeMembers;

    /** The sink receives the analyzed top-level declaration on the compiler callback thread. */
    public M3AstBindings(Consumer<? super TypeShape> sink) {
        this(sink, false);
    }

    private M3AstBindings(Consumer<? super TypeShape> sink, boolean includeMembers) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.includeMembers = includeMembers;
    }

    /** Reuse this identity to share exact-event work with another consumer. */
    public static M3AST.Projection<TypeShape> projection() { return SHAPE; }

    /**
     * Top-level declaration followed by its named member-nested declarations in source order.
     * Local and anonymous classes are excluded: they have no stable canonical name suitable
     * for this source/runtime matching API. This separate identity does not change projection().
     */
    public static M3AST.Projection<List<TypeShape>> allDeclarationsProjection() {
        return ALL_DECLARATIONS;
    }

    /** A consumer for allDeclarationsProjection(); the caller still controls result retention. */
    public static M3AstBindings allDeclarations(Consumer<? super TypeShape> sink) {
        return new M3AstBindings(sink, true);
    }

    @Override public void analyzed(M3AST.Unit unit) throws Exception {
        if (includeMembers) {
            for (TypeShape shape : unit.project(ALL_DECLARATIONS)) sink.accept(shape);
        } else {
            sink.accept(unit.project(SHAPE));
        }
    }

    /**
     * Java declaration type spelling, including generic arguments and array/wildcard structure.
     * Type-use annotations are excluded from javaType; annotations lists declaration names.
     * Type variables retain their declared names. Their bounds are not represented by this schema.
     */
    public record Member(String name, String javaType, List<String> annotations) {
        public Member {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(javaType, "javaType");
            annotations = List.copyOf(annotations);
        }
    }

    /** Source-sealed immutable facts; JSON serialization is ordinary Jackson binding. */
    public record TypeShape(String qualifiedName, String kind, URI sourceUri,
                            String sourceUtf16Sha256, List<Member> declaredFields,
                            List<Member> recordComponents) {
        public TypeShape {
            Objects.requireNonNull(qualifiedName, "qualifiedName");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sourceUri, "sourceUri");
            Objects.requireNonNull(sourceUtf16Sha256, "sourceUtf16Sha256");
            declaredFields = List.copyOf(declaredFields);
            recordComponents = List.copyOf(recordComponents);
        }
    }

    /**
     * Bind a caller-selected runtime class after checking declaration name, kind and record
     * component structure. Use the TypeReference or JavaType overload for a parameterized root.
     * These checks do not certify source versus bytecode equivalence, type-variable bounds,
     * annotations, inherited members, or an entire bean's property model.
     */
    public static <T> M3Binding<T> prepare(TypeShape shape, Class<T> type,
                                          M3ObjectMapper mapper) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
        return prepare(shape, mapper.getTypeFactory().constructType(type), mapper);
    }

    /** Resolves the full generic type once, then preserves that JavaType in the prepared binding. */
    public static <T> M3Binding<T> prepare(TypeShape shape, TypeReference<T> type,
                                          M3ObjectMapper mapper) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
        return prepare(shape, mapper.getTypeFactory().constructType(type), mapper);
    }

    /**
     * Uses the supplied resolved JavaType without replacing it with its raw Class.
     * Record component signatures are compared at declaration level: T remains T, so a source
     * Box&lt;T&gt; may bind Box&lt;Person&gt;. Type-variable names are compared conservatively; renaming
     * them can require recapturing the declaration. No source type string is parsed or loaded.
     */
    public static <T> M3Binding<T> prepare(TypeShape shape, JavaType type,
                                          M3ObjectMapper mapper) {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
        validate(shape, type.getRawClass());
        return mapper.prepare(type);
    }

    private static void validate(TypeShape shape, Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isLocalClass()
                || type.isAnonymousClass() || type.getCanonicalName() == null) {
            throw new IllegalArgumentException("runtime type must be a named class or member declaration");
        }
        if (!shape.qualifiedName().equals(type.getCanonicalName())) {
            throw new IllegalArgumentException("source declaration and runtime class names differ");
        }
        ElementKind kind = type.isAnnotation() ? ElementKind.ANNOTATION_TYPE
            : type.isEnum() ? ElementKind.ENUM
            : type.isRecord() ? ElementKind.RECORD
            : type.isInterface() ? ElementKind.INTERFACE : ElementKind.CLASS;
        if (!shape.kind().equals(kind.name())) {
            throw new IllegalArgumentException("source and runtime declaration kinds differ");
        }
        if (type.isRecord()) {
            var components = type.getRecordComponents();
            if (components.length != shape.recordComponents().size()) {
                throw new IllegalArgumentException("source and runtime record component counts differ");
            }
            for (int i = 0; i < components.length; i++) {
                if (!components[i].getName().equals(shape.recordComponents().get(i).name())) {
                    throw new IllegalArgumentException("source and runtime record component order differs");
                }
                String source = compact(shape.recordComponents().get(i).javaType());
                String runtime = compact(runtimeType(components[i].getGenericType()));
                if (!source.equals(runtime)) {
                    throw new IllegalArgumentException("source and runtime record component types differ: "
                        + components[i].getName());
                }
            }
        }
    }

    private static TypeShape capture(M3AST.Unit unit) throws IOException {
        if (unit.phase() != M3AST.Phase.ANALYZED) {
            throw new IllegalArgumentException("binding metadata requires attributed M3AST facts");
        }
        TypeElement type = Objects.requireNonNull(unit.analyzedType(), "analyzed type");
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            throw new IllegalArgumentException("analyzed event must identify a top-level declaration");
        }
        M3AST.Source source = unit.source();
        return capture(type, source);
    }

    private static List<TypeShape> captureAll(M3AST.Unit unit) throws Exception {
        TypeShape top = unit.project(SHAPE);
        M3AST.Source source = unit.source();
        List<TypeShape> result = new ArrayList<>();
        result.add(top);
        ArrayDeque<TypeElement> pending = new ArrayDeque<>();
        addMemberTypes(Objects.requireNonNull(unit.analyzedType(), "analyzed type"), pending);
        while (!pending.isEmpty()) {
            TypeElement type = pending.removeFirst();
            result.add(capture(type, source));
            addMemberTypes(type, pending);
        }
        return List.copyOf(result);
    }

    private static void addMemberTypes(TypeElement owner, ArrayDeque<TypeElement> pending) {
        List<? extends Element> members = owner.getEnclosedElements();
        for (int i = members.size() - 1; i >= 0; i--) {
            if (members.get(i) instanceof TypeElement type
                    && type.getNestingKind() == NestingKind.MEMBER) pending.addFirst(type);
        }
    }

    private static TypeShape capture(TypeElement type, M3AST.Source source) {
        List<Member> fields = type.getEnclosedElements().stream()
            .filter(e -> e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT)
            .map(M3AstBindings::member).toList();
        List<Member> components = type.getRecordComponents().stream()
            .map(M3AstBindings::member).toList();
        return new TypeShape(type.getQualifiedName().toString(), type.getKind().name(),
            source.uri(), source.utf16Sha256(), fields, components);
    }

    private static Member member(Element element) {
        return new Member(element.getSimpleName().toString(), sourceType(element.asType()),
            element.getAnnotationMirrors().stream()
                .map(annotation -> annotation.getAnnotationType().toString()).toList());
    }

    /** Reads attributed type structure; never reparses source text or retains a TypeMirror. */
    private static String sourceType(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE ->
                type.getKind().name().toLowerCase(Locale.ROOT);
            case ARRAY -> sourceType(((ArrayType) type).getComponentType()) + "[]";
            case TYPEVAR -> ((javax.lang.model.type.TypeVariable) type).asElement()
                .getSimpleName().toString();
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                TypeElement element = (TypeElement) declared.asElement();
                String name = element.getQualifiedName().toString();
                if (name.isEmpty()) throw new IllegalArgumentException("unnamed source declaration type");
                TypeMirror owner = declared.getEnclosingType();
                if (owner.getKind() == TypeKind.DECLARED
                        && !element.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
                    name = sourceType(owner) + "." + element.getSimpleName();
                }
                yield withArguments(name, declared.getTypeArguments().stream()
                    .map(M3AstBindings::sourceType).toList());
            }
            case WILDCARD -> {
                var wildcard = (javax.lang.model.type.WildcardType) type;
                if (wildcard.getSuperBound() != null) {
                    yield "? super " + sourceType(wildcard.getSuperBound());
                }
                String upper = wildcard.getExtendsBound() == null
                    ? "java.lang.Object" : sourceType(wildcard.getExtendsBound());
                yield upper.equals("java.lang.Object") ? "?" : "? extends " + upper;
            }
            default -> throw new IllegalArgumentException("unsupported or unresolved source type: "
                + type.getKind());
        };
    }

    /** Independently normalizes the actual runtime declaration's reflective type structure. */
    private static String runtimeType(Type type) {
        if (type instanceof Class<?> value) {
            if (value.isArray()) return runtimeType(value.getComponentType()) + "[]";
            String name = value.getCanonicalName();
            if (name == null) throw new IllegalArgumentException("unnamed runtime declaration type");
            return name;
        }
        if (type instanceof ParameterizedType value) {
            if (!(value.getRawType() instanceof Class<?> raw)) {
                throw new IllegalArgumentException("parameterized runtime type has no raw Class");
            }
            String name = runtimeType(raw);
            if (value.getOwnerType() != null && raw.getEnclosingClass() != null
                    && !Modifier.isStatic(raw.getModifiers())) {
                name = runtimeType(value.getOwnerType()) + "." + raw.getSimpleName();
            }
            return withArguments(name, Arrays.stream(value.getActualTypeArguments())
                .map(M3AstBindings::runtimeType).toList());
        }
        if (type instanceof GenericArrayType value) {
            return runtimeType(value.getGenericComponentType()) + "[]";
        }
        if (type instanceof TypeVariable<?> value) return value.getName();
        if (type instanceof WildcardType value) {
            Type[] lower = value.getLowerBounds();
            Type[] upper = value.getUpperBounds();
            if (lower.length == 1) return "? super " + runtimeType(lower[0]);
            if (lower.length != 0 || upper.length != 1) {
                throw new IllegalArgumentException("unsupported runtime wildcard bounds");
            }
            String bound = runtimeType(upper[0]);
            return bound.equals("java.lang.Object") ? "?" : "? extends " + bound;
        }
        throw new IllegalArgumentException("unsupported runtime declaration type: " + type.getTypeName());
    }

    private static String withArguments(String name, List<String> arguments) {
        return arguments.isEmpty() ? name : name + "<" + String.join(", ", arguments) + ">";
    }

    /** Formatting whitespace carries no Java type identity; literal '$' identifiers are retained. */
    private static String compact(String spelling) {
        StringBuilder result = new StringBuilder(spelling.length());
        for (int i = 0; i < spelling.length(); i++) {
            char value = spelling.charAt(i);
            if (!Character.isWhitespace(value)) result.append(value);
        }
        return result.toString();
    }
}

/* Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package jdk.m3.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Real Jackson differential oracle; run unchanged in host and linked module images. */
public final class JacksonContract {
    private static int checks;
    private JacksonContract() { }

    public record Person(@JsonProperty("full_name") String name, int age,
                         List<String> tags) { }
    public record Box<T>(T value) { }
    public record Point(int x) { }
    public static final class Bean {
        @JsonProperty("renamed") private String value;
        @JsonIgnore public int ignored = 91;
        public Bean() { }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static void main(String[] args) throws Exception {
        M3ObjectMapper mapper = new M3ObjectMapper();
        ObjectMapper oracle = new ObjectMapper();
        String[] valid = {"null", "true", "false", "0", "-0", "1.00", "1e100",
            "9223372036854775808", "[]", "{}", "[null,true,1,\"two\"]",
            "{\"b\":1,\"a\":2,\"b\":3}", "{\"empty\":\"\",\"nested\":{\"x\":[1,2]}}",
            "\"A\\n\\t\\u0000\\uD83D\\uDE03\"", "\"हिन्दी 日本語 😀\"",
            "\"unpaired \\uD800\"", " { \"x\" : 3 } \r\n", "1 2", ""};
        for (String json : valid) {
            JsonNode expected = oracle.readTree(json);
            for (int split = 0; split <= json.length(); split++) {
                M3File source = M3File.of(json.substring(0, split), "", json.substring(split));
                equal(expected, mapper.readTree(source), "tree at token seam " + split);
            }
        }
        String[] invalid = {"[", "{\"x\":}", "{\"x\":1,}", "[1,]", "\"\\q\"",
            "01", "tru", "{unquoted:1}", "\"line\nline\"", "\"\\u12\""};
        for (String json : invalid) {
            equal(failure(() -> oracle.readTree(json)),
                  failure(() -> mapper.readTree(parts(json))), "syntax exception class");
        }
        M3ObjectMapper strict = mapper.copy();
        strict.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        ObjectMapper strictOracle = oracle.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        equal(failure(() -> strictOracle.readTree("1 2")),
              failure(() -> strict.readTree(parts("1 2"))), "trailing token policy");
        strict.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strictOracle.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        String duplicates = "{\"x\":1,\"x\":2}";
        equal(failure(() -> strictOracle.readTree(duplicates)),
              failure(() -> strict.readTree(parts(duplicates))), "duplicate field policy");
        equal(2, mapper.readTree(parts(duplicates)).get("x").intValue(), "copy is isolated");

        Person person = new Person("हिन्दी 😀", 31, List.of("a", "b"));
        String json = oracle.writeValueAsString(person);
        equal(person, mapper.readValue(parts(json), Person.class), "record/annotation binding");
        equal(json, mapper.writeValueAsString(person), "upstream serializer");
        TypeReference<List<Box<Person>>> generic = new TypeReference<>() { };
        List<Box<Person>> boxes = List.of(new Box<>(person));
        String boxed = oracle.writeValueAsString(boxes);
        equal(boxes, mapper.readValue(parts(boxed), generic), "nested generic type reference");
        equal(boxes, mapper.readValue(parts(boxed), mapper.constructType(generic)), "JavaType");
        Bean bean = mapper.readValue(parts("{\"renamed\":\"ok\"}"), Bean.class);
        equal("ok", bean.getValue(), "private annotated field");
        equal(oracle.writeValueAsString(bean), mapper.writeValueAsString(bean), "ignore/rename");
        String unknown = "{\"full_name\":\"x\",\"age\":1,\"tags\":[],\"extra\":9}";
        equal(failure(() -> oracle.readValue(unknown, Person.class)),
              failure(() -> mapper.readValue(parts(unknown), Person.class)), "unknown property");

        M3ObjectMapper decimals = mapper.copy();
        decimals.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        Map<String, BigDecimal> decimal = decimals.readValue(parts("{\"n\":1.2300}"),
            new TypeReference<Map<String, BigDecimal>>() { });
        equal(new BigDecimal("1.2300"), decimal.get("n"), "decimal scale");
        customModule(mapper);
        preparedBinding(mapper, person);
        nio(mapper, person);
        equal(M3ObjectMapper.class, mapper.copyWith(new JsonFactory()).getClass(), "copyWith type");
        equal(mapper.getRegisteredModuleIds(), mapper.copy().getRegisteredModuleIds(), "module copy");
        System.out.println("JACKSON_CONTRACT_PASS checks=" + checks + " version=" + mapper.version());
    }

    private static void customModule(M3ObjectMapper mapper) throws Exception {
        SimpleModule module = new SimpleModule("m3-contract-module");
        module.addSerializer(Point.class, new JsonSerializer<Point>() {
            @Override public void serialize(Point p, JsonGenerator g, SerializerProvider s) throws IOException {
                g.writeNumber(p.x() + 10);
            }
        });
        module.addDeserializer(Point.class, new JsonDeserializer<Point>() {
            @Override public Point deserialize(JsonParser p, DeserializationContext c) throws IOException {
                return new Point(p.getIntValue() - 10);
            }
        });
        mapper.registerModule(module);
        equal(new Point(7), mapper.readValue(parts("17"), Point.class), "registered deserializer");
        equal("17", mapper.writeValueAsString(new Point(7)), "registered serializer");
        equal(new Point(2), mapper.copy().readValue(parts("12"), Point.class), "copied module");
    }

    private static void preparedBinding(M3ObjectMapper mapper, Person person) throws Exception {
        M3Binding<Person> binding = mapper.prepare(Person.class);
        String expected = binding.writeValueAsString(person);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        equal(expected, binding.writeValueAsString(person), "writer configuration snapshot");
        equal(person, binding.readValue(parts(expected)), "prepared indexed input");
        equal(person, binding.readValue(expected), "prepared String input");
        M3Binding<List<Person>> listBinding = mapper.prepare(new TypeReference<List<Person>>() { });
        equal(List.of(person), listBinding.readValue(parts("[" + expected + "]")), "prepared generic");
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Person>> work = new ArrayList<>();
            for (int i = 0; i < 48; i++) work.add(() -> binding.readValue(parts(expected)));
            for (var result : executor.invokeAll(work)) equal(person, result.get(), "concurrent prepared reader");
        }
    }

    private static void nio(M3ObjectMapper mapper, Person person) throws Exception {
        Path root = Files.createTempDirectory("m3-jackson-contract-");
        try {
            Path path = root.resolve("दस्तावेज.json");
            mapper.writeValue(path, person);
            equal(person, mapper.readValue(path, Person.class), "Path POJO");
            equal(mapper.readTree(path.toFile()), mapper.readTree(path), "Path tree");
            equal(person, mapper.readValue(path, mapper.constructType(Person.class)), "Path JavaType");
            M3Binding<Person> binding = mapper.prepare(Person.class);
            binding.writeValue(path, person);
            equal(person, binding.readValue(path), "prepared Path");
            Path array = root.resolve("array.json");
            mapper.writeValue(array, List.of(person));
            equal(List.of(person), mapper.readValue(array, new TypeReference<List<Person>>() { }),
                  "Path generic");
            Path utf16 = root.resolve("utf16.json");
            Files.write(utf16, "\uFEFF{\"n\":17}".getBytes(StandardCharsets.UTF_16LE));
            equal(17, mapper.readTree(utf16).get("n").intValue(), "Jackson byte encoding detection");
            Files.writeString(path, "[");
            equal(failure(() -> new ObjectMapper().readTree(path.toFile())),
                  failure(() -> mapper.readTree(path)), "Path syntax errors");
            truth(failure(() -> mapper.readTree(root.resolve("missing.json"))) != null,
                  "Path missing error");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static M3File parts(String text) {
        String[] values = new String[text.length()];
        for (int i = 0; i < values.length; i++) values[i] = text.substring(i, i + 1);
        return M3File.of(values);
    }

    @FunctionalInterface private interface Action { Object run() throws Exception; }
    private static Class<?> failure(Action action) throws Exception {
        try { action.run(); return null; }
        catch (IOException | IllegalArgumentException failure) { return failure.getClass(); }
    }
    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": " + expected + " != " + actual);
    }
    private static void truth(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

/*
 * Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package jdk.m3.jackson;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.FormatSchema;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.format.InputAccessor;
import com.fasterxml.jackson.core.format.MatchStrength;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Independent String/Writer and upstream Jackson oracles for the added I/O APIs. */
public final class JacksonStreamingContract {
    private static int checks;

    public record Item(@JsonProperty("key") String name, int count) { }
    public record InitializationTarget(int value) { }

    private JacksonStreamingContract() { }

    public static void main(String[] args) throws Exception {
        indexedOutput();
        indexedAdmission();
        mapperOutput();
        sequences();
        ownershipAndFailures();
        sequenceInitializationAndClose();
        sequenceReaderConfiguration();
        System.out.println("JACKSON_STREAMING_PASS checks=" + checks);
    }

    private static void indexedOutput() throws Exception {
        Random random = new Random(0x4d334a41434b534fL);
        for (int trial = 0; trial < 160; trial++) {
            StringWriter expected = new StringWriter();
            M3File.Writer actual = M3File.newWriter(20000);
            for (int step = 0; step < 40; step++) {
                char[] chars = new char[random.nextInt(45)];
                for (int i = 0; i < chars.length; i++) chars[i] = (char) random.nextInt(65536);
                String text = new String(chars);
                switch (random.nextInt(5)) {
                    case 0 -> { expected.write(chars); actual.write(chars); }
                    case 1 -> { expected.write(text); actual.write(text); }
                    case 2 -> {
                        M3File piece = M3File.of("", text.substring(0, text.length() / 2),
                                "", text.substring(text.length() / 2), "");
                        expected.append(piece); actual.append(piece);
                    }
                    case 3 -> {
                        StringBuilder piece = new StringBuilder("x").append(text).append("y");
                        expected.append(piece, 1, piece.length() - 1);
                        actual.append(piece, 1, piece.length() - 1);
                    }
                    default -> { expected.append((CharSequence) null); actual.append((CharSequence) null); }
                }
                if (random.nextBoolean()) actual.flush();
            }
            M3File file = actual.finish();
            equal(expected.toString(), file.toString(), "random Writer oracle");
            same(file, actual.finish(), "finish identity");
            actual.close();
            same(file, actual.finish(), "result remains after close");
            expect(IOException.class, () -> actual.write('x'), "closed writer");
            expect(IOException.class, actual::flush, "closed flush");
        }
        M3File.Writer bounded = M3File.newWriter(4);
        bounded.write("abc");
        expect(IOException.class, () -> bounded.write("de"), "limit is checked before a write");
        equal("abc", bounded.finish().toString(), "rejected write keeps prefix");
        M3File.Writer empty = M3File.newWriter(0);
        empty.write("");
        expect(IOException.class, () -> empty.write('x'), "zero limit");
        equal("", empty.finish().toString(), "empty output");
        expect(IllegalArgumentException.class, () -> M3File.newWriter(-1), "negative limit");
        M3File.Writer snapshot = M3File.newWriter();
        char[] mutable = {'a', '\ud83d', '\ude03', 'z'};
        snapshot.write(mutable);
        Arrays.fill(mutable, 'x');
        equal("a\ud83d\ude03z", snapshot.finish().toString(), "mutable input is snapshotted");
        M3File.Writer repeated = M3File.newWriter();
        repeated.write("r".repeat(8192 * 5));
        M3File repeatedFile = repeated.finish();
        equal(5, repeatedFile.sourceTokenCount(), "bounded output chunks");
        equal(1, repeatedFile.sourceUniqueTokenCount(), "equal chunks reuse an atom");
        TrackingWriter destination = new TrackingWriter();
        M3File view = M3File.of("xx", "a\ud83d", "", "\ude03z", "yy").slice(2, 6);
        equal(4L, view.writeTo(destination), "writeTo length");
        equal("a\ud83d\ude03z", destination.text.toString(), "writeTo seam");
        truth(!destination.closed && !destination.flushed, "borrowed output remains caller-owned");
    }

    private static void indexedAdmission() throws Exception {
        String text = ("{\"emoji\":\"\ud83d\ude03\",\"n\":12}\n").repeat(700);
        TrackingReader source = new TrackingReader(text);
        M3File admitted = M3File.read(source, text.length());
        equal(text, admitted.toString(), "Reader admission");
        truth(admitted.sourceTokenCount() > 1, "Reader admission is chunked");
        truth(!source.closed, "borrowed reader stays open");
        TrackingReader limited = new TrackingReader("12345");
        expect(IOException.class, () -> M3File.read(limited, 4), "Reader admission limit");
        truth(!limited.closed, "failed admission keeps borrowed reader open");
        TrackingReader broken = new TrackingReader("data");
        broken.fail = true;
        expect(IOException.class, () -> M3File.read(broken, 20), "read failure");
        truth(!broken.closed, "read failure keeps borrowed reader open");
        Reader zeroOnce = new Reader() {
            private final StringReader delegate = new StringReader("abc");
            private boolean first = true;
            @Override public int read(char[] target, int offset, int count) throws IOException {
                if (first) { first = false; return 0; }
                return delegate.read(target, offset, count);
            }
            @Override public int read() throws IOException { return delegate.read(); }
            @Override public void close() throws IOException { delegate.close(); }
        };
        equal("abc", M3File.read(zeroOnce, 3).toString(), "zero-return reader makes progress");
        zeroOnce.close();
        Path file = Files.createTempFile("m3-indexed-admission-", ".txt");
        try {
            Files.writeString(file, text, StandardCharsets.UTF_16LE);
            equal(text, M3File.read(file, StandardCharsets.UTF_16LE, text.length()).toString(),
                    "explicit Path decoding");
        } finally { Files.deleteIfExists(file); }
    }

    private static void mapperOutput() throws Exception {
        M3ObjectMapper mapper = new M3ObjectMapper();
        ObjectMapper oracle = new ObjectMapper();
        List<Object> values = Arrays.asList(null, true, 42, new BigDecimal("123456789.000001"),
                new Item("\ud83d\ude03", 9), Map.of("long", "q".repeat(30000)),
                List.of(new Item("first", 1), new Item("second", 2)));
        for (boolean pretty : List.of(false, true)) {
            mapper.configure(SerializationFeature.INDENT_OUTPUT, pretty);
            oracle.configure(SerializationFeature.INDENT_OUTPUT, pretty);
            for (Object value : values) {
                M3File file = mapper.writeValueAsM3File(value);
                equal(oracle.writeValueAsString(value), file.toString(), "ObjectMapper output oracle");
                equal(oracle.readTree(file.toString()), mapper.readTree(file), "indexed output round trip");
            }
        }
        M3Binding<Item> binding = mapper.prepare(Item.class);
        Item item = new Item("bound", 17);
        M3File output = binding.writeValueAsM3File(item);
        equal(binding.writeValueAsString(item), output.toString(), "typed output oracle");
        equal(item, binding.readValue(output), "typed indexed round trip");
        expect(IOException.class, () -> mapper.writeValueAsM3File(item, 2), "mapper output limit");
        expect(IOException.class, () -> binding.writeValueAsM3File(item, 2), "binding output limit");
        equal(item, mapper.copy().readValue(mapper.copy().writeValueAsM3File(item), Item.class),
                "mapper copy retains new APIs");
    }

    private static void sequences() throws Exception {
        M3ObjectMapper mapper = new M3ObjectMapper();
        M3Binding<Item> binding = mapper.prepare(Item.class);
        List<Item> expected = List.of(new Item("one", 1), new Item("two", 2));
        for (boolean array : List.of(false, true)) {
            M3File file = binding.writeValuesAsM3File(expected, array, 10000);
            StringWriter oracle = new StringWriter();
            try (var sequence = array ? binding.writer().writeValuesAsArray(oracle)
                    : binding.writer().writeValues(oracle)) {
                sequence.writeAll(expected);
            }
            equal(oracle.toString(), file.toString(), "sequence Writer oracle");
            M3File seams = oneUnitPieces(file.toString());
            try (var values = binding.readValues(seams)) { equal(expected, values.readAll(), "binding sequence"); }
            try (var values = binding.readValues(file.toString())) { equal(expected, values.readAll(), "String sequence"); }
            try (var values = mapper.readValues(seams, Item.class)) { equal(expected, values.readAll(), "Class sequence"); }
            try (var values = mapper.readValues(seams, new TypeReference<Item>() { })) {
                equal(expected, values.readAll(), "TypeReference sequence");
            }
            JavaType type = mapper.constructType(Item.class);
            try (MappingIterator<Item> values = mapper.readValues(seams, type)) {
                equal(expected, values.readAll(), "JavaType sequence");
            }
        }
        List<Item> nullable = Arrays.asList(expected.get(0), null, expected.get(1));
        M3File nullableOutput = binding.writeValuesAsM3File(nullable, true, 10000);
        StringWriter nullableOracle = new StringWriter();
        try (var values = binding.writer().writeValuesAsArray(nullableOracle)) {
            values.writeAll(nullable);
        }
        equal(nullableOracle.toString(), nullableOutput.toString(), "null sequence output oracle");
        // MappingIterator calls the selected deserializer directly. Its null policy is
        // type-specific; a record root null is rejected by the upstream bean reader.
        try (MappingIterator<Item> values = binding.reader().readValues(nullableOracle.toString())) {
            expect(IOException.class, values::readAll, "upstream record-null sequence policy");
        }
        try (var values = binding.readValues(nullableOutput)) {
            expect(IOException.class, values::readAll, "indexed record-null sequence parity");
        }
        M3Binding<Integer> integers = mapper.prepare(Integer.class);
        List<Integer> nullableNumbers = Arrays.asList(1, null, 2);
        try (var values = integers.readValues(integers.writeValuesAsM3File(nullableNumbers, true, 50));
             MappingIterator<Integer> oracle = integers.reader().readValues("[1,null,2]")) {
            equal(oracle.readAll(), values.readAll(), "numeric null sequence parity");
        }
        TypeReference<List<Item>> generic = new TypeReference<>() { };
        M3Binding<List<Item>> groups = mapper.prepare(generic);
        List<List<Item>> expectedGroups = List.of(List.of(new Item("a", 1)), List.of(new Item("b", 2)));
        M3File nested = groups.writeValuesAsM3File(expectedGroups, true, 10000);
        try (var values = mapper.readValues(nested, generic)) {
            equal(expectedGroups, values.readAll(), "nested array generic sequence");
        }
        try (var values = binding.readValues(M3File.of("", " ", ""))) {
            truth(!values.hasNextValue(), "empty sequence");
            expect(java.util.NoSuchElementException.class, values::nextValue, "empty nextValue");
        }
        equal("[]", binding.writeValuesAsM3File(List.of(), true, 2).toString(), "empty array output");
        equal("", binding.writeValuesAsM3File(List.of()).toString(), "empty root output");
        expect(IOException.class, () -> binding.writeValuesAsM3File(expected, true, 5), "sequence output limit");
        try (var values = binding.readValues(M3File.of(
                "[{\"key\":\"one\",\"count\":1},\"wrong\",{\"key\":\"two\",\"count\":2}]"))) {
            equal(expected.get(0), values.nextValue(), "value before recoverable error");
            expect(IOException.class, values::nextValue, "recoverable binding error");
            equal(expected.get(1), values.nextValue(), "upstream iterator resynchronization");
            truth(!values.hasNextValue(), "recovered iterator exhausted");
        }
    }

    private static void ownershipAndFailures() throws Exception {
        CaptureFactory factory = new CaptureFactory();
        factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
        factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        M3ObjectMapper mapper = new M3ObjectMapper(factory);
        M3Binding<Item> binding = mapper.prepare(Item.class);
        Item item = new Item("owned", 7);
        String json = mapper.writeValueAsString(item);
        try (var values = binding.readValues(M3File.of(json, " ", json))) {
            equal(item, values.nextValue(), "early value");
            truth(factory.inputReader.ready(), "owned reader live before close");
        }
        expect(IOException.class, factory.inputReader::read, "early close releases indexed reader");
        try (var values = binding.readValues(M3File.of("[", json, "]"))) {
            equal(List.of(item), values.readAll(), "wrapped managed iterator");
            expect(IOException.class, factory.inputReader::read, "EOF releases indexed reader");
        }
        try (var values = binding.readValues(M3File.of(json, " {broken"))) {
            equal(item, values.nextValue(), "value before malformed input");
            expect(IOException.class, values::nextValue, "malformed sequence");
        }
        expect(IOException.class, factory.inputReader::read, "failure close releases indexed reader");
        factory.failParser = true;
        expect(IOException.class, () -> binding.readValues(M3File.of(json)), "parser initialization failure");
        expect(IOException.class, factory.inputReader::read, "initialization failure releases reader");
        factory.failParser = false;
        Path directory = Files.createTempDirectory("m3-jackson-sequence-");
        Path input = directory.resolve("input.json");
        Path output = directory.resolve("output.json");
        try {
            Files.writeString(input, "[" + json + "," + json + "]", StandardCharsets.UTF_16);
            try (var values = binding.readValues(input)) {
                equal(List.of(item, item), values.readAll(), "Path sequence BOM");
                expect(IOException.class, factory.inputStream::read, "EOF releases Path input");
            }
            try (var values = mapper.readValues(input, Item.class)) {
                equal(item, values.nextValue(), "Path Class overload");
            }
            expect(IOException.class, factory.inputStream::read, "early close releases Path input");
            try (var values = mapper.readValues(input, new TypeReference<Item>() { })) {
                equal(List.of(item, item), values.readAll(), "Path TypeReference overload");
            }
            try (MappingIterator<Item> values = mapper.readValues(input, mapper.constructType(Item.class))) {
                equal(List.of(item, item), values.readAll(), "Path JavaType overload");
            }
            binding.writeValues(output, List.of(item, item));
            expect(IOException.class, () -> factory.outputStream.write(0), "root sequence releases Path output");
            try (var values = binding.readValues(output)) {
                equal(List.of(item, item), values.readAll(), "root Path sequence round trip");
            }
            binding.writeValuesAsArray(output, List.of(item));
            expect(IOException.class, () -> factory.outputStream.write(0), "array sequence releases Path output");
            equal("[" + json + "]", Files.readString(output), "array Path output");
            Iterable<Item> broken = () -> new Iterator<>() {
                @Override public boolean hasNext() { throw new IllegalStateException("iteration failure"); }
                @Override public Item next() { throw new AssertionError("unreachable"); }
            };
            expect(IllegalStateException.class, () -> binding.writeValuesAsArray(output, broken),
                    "iteration failure remains primary");
            expect(IOException.class, () -> factory.outputStream.write(0), "iteration failure releases output");
            factory.failParser = true;
            expect(IOException.class, () -> binding.readValues(input), "Path parser initialization failure");
            expect(IOException.class, factory.inputStream::read, "failed Path parser releases input");
            factory.failParser = false;
            factory.failGenerator = true;
            expect(IOException.class, () -> binding.writeValues(output, List.of(item)),
                    "generator initialization failure");
            expect(IOException.class, () -> factory.outputStream.write(0), "failed generator releases output");
            factory.failGenerator = false;
            equal(item, binding.readValue(binding.writeValueAsM3File(item)), "AUTO_CLOSE disabled indexed output");
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(directory);
        }
    }

    private static void sequenceInitializationAndClose() throws Exception {
        CaptureFactory factory = new CaptureFactory();
        factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
        factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        M3ObjectMapper mapper = new M3ObjectMapper(factory);
        M3Binding<Integer> binding = mapper.prepare(Integer.class);
        Path file = Files.createTempFile("m3-jackson-lifecycle-", ".json");
        try {
            // These failures occur after a real parser exists, before an iterator
            // is returned. Throwing in createParser itself does not cover them.
            expect(JsonParseException.class, () -> binding.readValues(M3File.of("%")),
                    "indexed first-token failure");
            truth(factory.parser.isClosed(), "first-token failure closes parser");
            expect(IOException.class, factory.inputReader::read,
                    "first-token failure closes indexed input");
            Files.writeString(file, "%");
            expect(JsonParseException.class, () -> binding.readValues(file), "Path first-token failure");
            truth(factory.parser.isClosed(), "Path first-token failure closes parser");
            expect(IOException.class, factory.inputStream::read,
                    "first-token failure closes Path input");

            ObjectReader configured = mapper.readerFor(Integer.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            M3Binding<Integer> configuredBinding = new M3Binding<>(mapper.constructType(Integer.class),
                    configured, mapper.writerFor(Integer.class));
            factory.failParserConfiguration = true;
            expect(IllegalStateException.class,
                    () -> configuredBinding.readValues(M3File.of("[1]")), "parser configuration failure");
            truth(factory.parser.isClosed(), "configuration failure closes parser");
            expect(IOException.class, factory.inputReader::read,
                    "configuration failure closes indexed input");
            factory.failParserConfiguration = false;

            M3ObjectMapper contextualMapper = new M3ObjectMapper(factory);
            contextualMapper.disable(DeserializationFeature.EAGER_DESERIALIZER_FETCH);
            SimpleModule module = new SimpleModule("sequence-initialization-contract");
            module.addDeserializer(InitializationTarget.class, new InitializationDeserializer());
            contextualMapper.registerModule(module);
            M3Binding<InitializationTarget> contextual = contextualMapper.prepare(InitializationTarget.class);
            expect(JsonMappingException.class, () -> contextual.readValues(M3File.of("{}")),
                    "contextual root initialization failure");
            truth(factory.parser.isClosed(), "contextual initialization closes parser");
            expect(IOException.class, factory.inputReader::read,
                    "contextual initialization closes indexed input");

            factory.failParserClose = true;
            Throwable parseFailure = failure(JsonParseException.class,
                    () -> binding.readValues(M3File.of("%")), "parse failure remains primary");
            truth(hasSuppressed(parseFailure, "parser close failure"),
                    "parser close failure is suppressed during initialization");
            truth(factory.parser.isClosed(), "failing parser close still releases parser");
            expect(IOException.class, factory.inputReader::read,
                    "failing parser close still releases input");
            try (var values = binding.readValues(M3File.of("[1]"))) {
                equal(1, values.nextValue(), "value before failing EOF close");
                expect(IOException.class, values::hasNextValue, "EOF parser close failure");
                truth(factory.parser.isClosed(), "EOF parser is closed after close failure");
                expect(IOException.class, factory.inputReader::read, "EOF close failure releases input");
                truth(!values.hasNextValue(), "closed iterator remains exhausted after close failure");
            }
            factory.failParserClose = false;

            factory.setStreamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(0).build());
            expect(IOException.class, () -> binding.writeValuesAsM3File(List.of(1), true, 20),
                    "array initialization constraint failure");
            truth(factory.generator.isClosed(), "array initialization failure closes generator");
            expect(IOException.class, () -> factory.outputWriter.write('x'),
                    "array initialization failure closes indexed output");
            expect(IOException.class, () -> binding.writeValuesAsArray(file, List.of(1)),
                    "Path array initialization constraint failure");
            truth(factory.generator.isClosed(), "Path array initialization closes generator");
            expect(IOException.class, () -> factory.outputStream.write('x'),
                    "Path array initialization closes output");
            factory.setStreamWriteConstraints(StreamWriteConstraints.defaults());

            ObjectWriter configuredWriter = mapper.writerFor(Integer.class)
                    .with(JsonGenerator.Feature.STRICT_DUPLICATE_DETECTION);
            M3Binding<Integer> configuredOutput = new M3Binding<>(mapper.constructType(Integer.class),
                    mapper.readerFor(Integer.class), configuredWriter);
            factory.failGeneratorConfiguration = true;
            expect(IllegalStateException.class,
                    () -> configuredOutput.writeValuesAsM3File(List.of(1)), "generator configuration failure");
            truth(factory.generator.isClosed(), "generator configuration failure closes generator");
            expect(IOException.class, () -> factory.outputWriter.write('x'),
                    "generator configuration failure closes indexed output");
            factory.failGeneratorConfiguration = false;

            factory.failArrayEnd = true;
            Throwable endFailure = failure(IOException.class,
                    () -> binding.writeValuesAsM3File(List.of(1), true, 20), "sequence end-array failure");
            equal("end-array failure", endFailure.getMessage(), "end-array failure remains primary");
            truth(factory.generator.isClosed(), "end-array failure closes generator");
            expect(IOException.class, () -> factory.outputWriter.write('x'),
                    "end-array failure closes indexed output");
            expect(IOException.class, () -> binding.writeValuesAsArray(file, List.of(1)),
                    "Path end-array failure");
            truth(factory.generator.isClosed(), "Path end-array failure closes generator");
            expect(IOException.class, () -> factory.outputStream.write('x'),
                    "Path end-array failure closes output");
            factory.failArrayEnd = false;

            factory.failGeneratorClose = true;
            expect(IOException.class, () -> binding.writeValuesAsM3File(List.of(1)),
                    "generator close failure");
            truth(factory.generator.isClosed(), "generator close failure releases generator");
            expect(IOException.class, () -> factory.outputWriter.write('x'),
                    "generator close failure releases indexed output");
            Iterable<Integer> broken = () -> new Iterator<>() {
                @Override public boolean hasNext() { throw new IllegalStateException("iteration failure"); }
                @Override public Integer next() { throw new AssertionError("unreachable"); }
            };
            Throwable iterationFailure = failure(IllegalStateException.class,
                    () -> binding.writeValuesAsM3File(broken, true, 20),
                    "iteration failure remains primary when generator close fails");
            truth(hasSuppressed(iterationFailure, "generator close failure"),
                    "generator close failure is suppressed during iteration failure");
            truth(factory.generator.isClosed(), "iteration failure releases generator");
            expect(IOException.class, () -> factory.outputWriter.write('x'),
                    "iteration failure releases indexed output despite close failure");
            factory.failGeneratorClose = false;
        } finally { Files.deleteIfExists(file); }
    }

    private static void sequenceReaderConfiguration() throws Exception {
        CaptureFactory factory = new CaptureFactory();
        factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
        factory.acceptSchema = true;
        M3ObjectMapper mapper = new M3ObjectMapper(factory);
        FormatSchema schema = () -> "sequence-contract";
        ObjectReader reader = mapper.readerFor(Integer.class).with(schema)
                .with(JsonReadFeature.ALLOW_JAVA_COMMENTS).at("/values");
        M3Binding<Integer> binding = new M3Binding<>(mapper.constructType(Integer.class),
                reader, mapper.writerFor(Integer.class));
        String input = "/* configured parser */ {\"values\":[1,2],\"ignored\":3}";
        List<Integer> expected;
        try (Reader source = new StringReader(input);
             MappingIterator<Integer> oracle = reader.readValues(source)) {
            expected = oracle.readAll();
        }
        equal(List.of(1, 2), expected, "upstream pointer-filter sequence policy");
        int schemaApplications = factory.schemaApplications;
        factory.schemaApplications = 0;
        try (var values = binding.readValues(oneUnitPieces(input))) {
            same(schema, values.getParserSchema(), "configured schema reaches managed parser");
            equal(expected, values.readAll(), "filter and parser-feature sequence oracle");
        }
        equal(schemaApplications, factory.schemaApplications,
                "schema initialization count matches ordinary reader");

        Path file = Files.createTempFile("m3-jackson-detection-", ".json");
        try {
            Files.writeString(file, "[1,2]");
            ObjectReader matched = mapper.readerFor(Object.class).with(schema)
                    .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            ObjectReader detecting = mapper.readerFor(Object.class).withFormatDetection(matched);
            M3Binding<Object> detected = new M3Binding<>(mapper.constructType(Object.class),
                    detecting, mapper.writerFor(Object.class));
            factory.schemaApplications = 0;
            List<Object> detectedExpected;
            try (InputStream source = Files.newInputStream(file);
                 MappingIterator<Object> oracle = detecting.readValues(source)) {
                detectedExpected = oracle.readAll();
            }
            equal(List.of(BigInteger.ONE, BigInteger.TWO), detectedExpected,
                    "matched reader owns detected databind configuration");
            int detectedSchemaApplications = factory.schemaApplications;
            factory.schemaApplications = 0;
            try (var values = detected.readValues(file)) {
                same(schema, values.getParserSchema(), "detected reader schema reaches parser");
                equal(detectedExpected, values.readAll(), "matched format-reader oracle");
            }
            equal(detectedSchemaApplications, factory.schemaApplications,
                    "detected schema initialization count matches ordinary reader");
            truth(factory.parser.isClosed(), "detected sequence closes parser on exhaustion");
            expect(JsonParseException.class, () -> detected.readValues(M3File.of("[1,2]")),
                    "format detection retains upstream character-source rejection");

            ObjectReader failingMatched = matched.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            M3Binding<Object> failingDetection = new M3Binding<>(mapper.constructType(Object.class),
                    mapper.readerFor(Object.class).withFormatDetection(failingMatched),
                    mapper.writerFor(Object.class));
            factory.failParserConfiguration = true;
            expect(IllegalStateException.class, () -> failingDetection.readValues(file),
                    "detected parser initialization failure");
            truth(factory.parser.isClosed(), "detected initialization failure closes parser");
            factory.failParserConfiguration = false;
        } finally { Files.deleteIfExists(file); }
    }

    private static boolean hasSuppressed(Throwable failure, String message) {
        return Arrays.stream(failure.getSuppressed()).anyMatch(item -> message.equals(item.getMessage()));
    }

    private static final class InitializationDeserializer extends JsonDeserializer<InitializationTarget>
            implements ContextualDeserializer {
        @Override public JsonDeserializer<?> createContextual(DeserializationContext context,
                BeanProperty property) throws JsonMappingException {
            throw JsonMappingException.from(context, "root initialization failure");
        }
        @Override public InitializationTarget deserialize(JsonParser parser, DeserializationContext context) {
            throw new AssertionError("root initialization must fail before deserialization");
        }
    }

    private static M3File oneUnitPieces(String value) {
        List<String> pieces = new ArrayList<>();
        pieces.add("");
        for (int i = 0; i < value.length(); i++) { pieces.add(value.substring(i, i + 1)); pieces.add(""); }
        return M3File.of(pieces);
    }

    private static final class TrackingReader extends Reader {
        private final StringReader source;
        private boolean closed;
        private boolean fail;
        private TrackingReader(String text) { source = new StringReader(text); }
        @Override public int read(char[] output, int offset, int count) throws IOException {
            if (fail) throw new IOException("reader failure");
            return source.read(output, offset, count);
        }
        @Override public void close() { closed = true; source.close(); }
    }

    private static final class TrackingWriter extends Writer {
        private final StringWriter text = new StringWriter();
        private boolean closed;
        private boolean flushed;
        @Override public void write(char[] value, int offset, int count) { text.write(value, offset, count); }
        @Override public void flush() { flushed = true; }
        @Override public void close() { closed = true; }
    }

    private static final class CaptureFactory extends JsonFactory {
        private static final long serialVersionUID = 1L;
        private transient Reader inputReader;
        private transient InputStream inputStream;
        private transient OutputStream outputStream;
        private transient Writer outputWriter;
        private transient JsonParser parser;
        private transient JsonGenerator generator;
        private boolean failParser;
        private boolean failGenerator;
        private boolean failParserConfiguration;
        private boolean failParserClose;
        private boolean failGeneratorConfiguration;
        private boolean failArrayEnd;
        private boolean failGeneratorClose;
        private boolean acceptSchema;
        private int schemaApplications;

        @Override public String getFormatName() { return FORMAT_NAME_JSON; }
        @Override public MatchStrength hasFormat(InputAccessor input) throws IOException {
            return hasJSONFormat(input);
        }

        @Override public boolean canUseSchema(FormatSchema schema) {
            return acceptSchema || super.canUseSchema(schema);
        }

        @Override public JsonParser createParser(Reader input) throws IOException {
            inputReader = input;
            if (failParser) throw new IOException("parser initialization failure");
            return capture(super.createParser(input));
        }
        @Override public JsonParser createParser(InputStream input) throws IOException {
            inputStream = input;
            if (failParser) throw new IOException("parser initialization failure");
            return capture(super.createParser(input));
        }
        private JsonParser capture(JsonParser created) {
            parser = new JsonParserDelegate(created) {
                private FormatSchema schema;
                @Override public void setSchema(FormatSchema value) {
                    if (!acceptSchema) { super.setSchema(value); return; }
                    schema = value;
                    schemaApplications++;
                }
                @Override public FormatSchema getSchema() {
                    return schema == null ? super.getSchema() : schema;
                }
                @Override public JsonParser overrideStdFeatures(int values, int mask) {
                    if (failParserConfiguration) throw new IllegalStateException("parser configuration failure");
                    return super.overrideStdFeatures(values, mask);
                }
                @Override public void close() throws IOException {
                    super.close();
                    if (failParserClose) throw new IOException("parser close failure");
                }
            };
            return parser;
        }
        @Override public JsonGenerator createGenerator(OutputStream output, JsonEncoding encoding)
                throws IOException {
            outputStream = output;
            if (failGenerator) throw new IOException("generator initialization failure");
            return capture(super.createGenerator(output, encoding));
        }
        @Override public JsonGenerator createGenerator(Writer output) throws IOException {
            outputWriter = output;
            if (failGenerator) throw new IOException("generator initialization failure");
            return capture(super.createGenerator(output));
        }
        private JsonGenerator capture(JsonGenerator created) {
            generator = new JsonGeneratorDelegate(created) {
                @Override public JsonGenerator overrideStdFeatures(int values, int mask) {
                    if (failGeneratorConfiguration) throw new IllegalStateException("generator configuration failure");
                    return super.overrideStdFeatures(values, mask);
                }
                @Override public void writeEndArray() throws IOException {
                    if (failArrayEnd) throw new IOException("end-array failure");
                    super.writeEndArray();
                }
                @Override public void close() throws IOException {
                    super.close();
                    if (failGeneratorClose) throw new IOException("generator close failure");
                }
            };
            return generator;
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Checked action, String message)
            throws Exception {
        failure(type, action, message);
    }
    private static Throwable failure(Class<? extends Throwable> type, Checked action, String message)
            throws Exception {
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) { checks++; return failure; }
            throw new AssertionError(message + ": wrong failure " + failure, failure);
        }
        throw new AssertionError(message + ": expected " + type.getName());
    }
    private static void equal(Object expected, Object actual, String message) {
        truth(Objects.equals(expected, actual), message + ": expected " + expected + ", actual " + actual);
    }
    private static void same(Object expected, Object actual, String message) {
        truth(expected == actual, message);
    }
    private static void truth(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

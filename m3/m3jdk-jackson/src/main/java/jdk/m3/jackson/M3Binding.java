/*
 * Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package jdk.m3.jackson;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.deser.DataFormatReaders;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Caller-owned prepared Jackson binding. The existing immutable ObjectReader
 * and ObjectWriter retain the selected type and immutable databind configuration;
 * no global cache or compiler objects are retained. Their JsonFactory and other
 * external collaborators remain shared according to Jackson's normal contract.
 * Finish configuring the mapper and factory before preparing a binding, and do
 * not mutate shared collaborators while using it. Custom serializers keep their
 * upstream thread safety and external-state obligations.
 */
public final class M3Binding<T> {
    private final JavaType type;
    private final ObjectReader reader;
    private final ObjectWriter writer;

    M3Binding(JavaType type, ObjectReader reader, ObjectWriter writer) {
        this.type = type;
        this.reader = reader;
        this.writer = writer;
    }

    public JavaType type() { return type; }
    public ObjectReader reader() { return reader; }
    public ObjectWriter writer() { return writer; }

    public T readValue(M3File source) throws IOException {
        try (var input = Objects.requireNonNull(source, "source").reader()) {
            return reader.readValue(input);
        }
    }

    public T readValue(String source) throws IOException {
        return reader.readValue(Objects.requireNonNull(source, "source"));
    }

    public T readValue(Path source) throws IOException {
        try (var input = Files.newInputStream(Objects.requireNonNull(source, "source"))) {
            return reader.readValue(input);
        }
    }

    public String writeValueAsString(T value) throws IOException {
        return writer.writeValueAsString(value);
    }

    /** Serializes directly to indexed String chunks, without a flat document String. */
    public M3File writeValueAsM3File(T value) throws IOException {
        return writeValueAsM3File(value, Integer.MAX_VALUE);
    }

    /** Serializes to indexed chunks while enforcing a UTF-16 output limit. */
    public M3File writeValueAsM3File(T value, int maxUtf16Length) throws IOException {
        try (M3File.Writer output = M3File.newWriter(maxUtf16Length)) {
            writer.writeValue(output, value);
            return output.finish();
        }
    }

    /**
     * Reads an array-wrapped or whitespace-separated root-value sequence using
     * Jackson's MappingIterator. Exhaustion or explicit close releases the owned
     * reader even when AUTO_CLOSE_SOURCE is disabled. Use try-with-resources when
     * stopping early or when input may be malformed; Jackson's recovery behavior
     * after a binding error remains available until the iterator is closed.
     */
    public MappingIterator<T> readValues(M3File source) throws IOException {
        return readSequence(reader, source);
    }

    /** Reads a sequence while retaining Jackson's byte-encoding/BOM detection. */
    public MappingIterator<T> readValues(Path source) throws IOException {
        return readSequence(reader, source);
    }

    /** Retains ObjectReader's ordinary String sequence semantics. */
    public MappingIterator<T> readValues(String source) throws IOException {
        return reader.readValues(Objects.requireNonNull(source, "source"));
    }

    /** Writes root values with this binding's configured Jackson root separator. */
    public void writeValues(Path destination, Iterable<? extends T> values) throws IOException {
        writeSequence(destination, values, false);
    }

    /** Writes all values as one JSON array, including an empty array for no values. */
    public void writeValuesAsArray(Path destination, Iterable<? extends T> values)
            throws IOException {
        writeSequence(destination, values, true);
    }

    private void writeSequence(Path destination, Iterable<? extends T> values, boolean array)
            throws IOException {
        Objects.requireNonNull(values, "values");
        try (var output = Files.newOutputStream(Objects.requireNonNull(destination, "destination"));
             JsonGenerator generator = writer.getFactory().createGenerator(output, JsonEncoding.UTF8);
             var sequence = array ? writer.writeValuesAsArray(generator) : writer.writeValues(generator)) {
            sequence.writeAll(values);
        }
    }

    /** Writes a root-value sequence into indexed text atoms. */
    public M3File writeValuesAsM3File(Iterable<? extends T> values) throws IOException {
        return writeValuesAsM3File(values, false, Integer.MAX_VALUE);
    }

    /**
     * Writes a bounded root-value sequence, optionally wrapped in one JSON array.
     * The sequence is consumed once; values and serializers keep Jackson's contracts.
     */
    public M3File writeValuesAsM3File(Iterable<? extends T> values, boolean array,
                                     int maxUtf16Length) throws IOException {
        Objects.requireNonNull(values, "values");
        try (M3File.Writer output = M3File.newWriter(maxUtf16Length)) {
            try (JsonGenerator generator = writer.getFactory().createGenerator(output);
                 var sequence = array ? writer.writeValuesAsArray(generator) : writer.writeValues(generator)) {
                sequence.writeAll(values);
            }
            return output.finish();
        }
    }

    public void writeValue(Path destination, T value) throws IOException {
        try (var output = Files.newOutputStream(Objects.requireNonNull(destination, "destination"))) {
            writer.writeValue(output, value);
        }
    }

    static <V> MappingIterator<V> readSequence(ObjectReader reader, M3File source)
            throws IOException {
        Reader input = Objects.requireNonNull(source, "source").reader();
        try (SequenceReader initializing = new SequenceReader(reader)) {
            MappingIterator<V> values = new OwnedValues<>(initializing.<V>readValues(input), input);
            initializing.transferParser();
            return values;
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(input, failure);
            throw failure;
        }
    }

    static <V> MappingIterator<V> readSequence(ObjectReader reader, Path source)
            throws IOException {
        InputStream input = Files.newInputStream(Objects.requireNonNull(source, "source"));
        try (SequenceReader initializing = new SequenceReader(reader)) {
            MappingIterator<V> values = new OwnedValues<>(initializing.<V>readValues(input), input);
            initializing.transferParser();
            return values;
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(input, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(Closeable input, Throwable failure) {
        try {
            input.close();
        } catch (IOException | RuntimeException | Error closeFailure) {
            if (closeFailure != failure) failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Temporary ownership during upstream ObjectReader sequence initialization.
     * A source overload may throw after creating its parser but before returning
     * an iterator. Capture the parser before configuration, keep the existing
     * managed sequence path, then transfer it only after OwnedValues exists.
     * Each call has its own scope; prepared readers remain immutable and shared.
     */
    private static final class SequenceReader extends ObjectReader implements Closeable {
        private static final long serialVersionUID = 1L;
        private transient JsonParser initializingParser;

        private SequenceReader(ObjectReader base) {
            super(base, base.getConfig());
        }

        @Override public JsonParser createParser(Reader input) throws IOException {
            _assertNotNull("r", input);
            initializingParser = _parserFactory.createParser(input);
            return _config.initialize(initializingParser, _schema);
        }

        @Override public JsonParser createParser(InputStream input) throws IOException {
            _assertNotNull("in", input);
            initializingParser = _parserFactory.createParser(input);
            return _config.initialize(initializingParser, _schema);
        }

        @Override protected <V> MappingIterator<V> _detectBindAndReadValues(
                DataFormatReaders.Match match, boolean forceClosing) throws IOException {
            if (!match.hasMatch()) _reportUnkownFormat(_dataFormatReaders, match);
            initializingParser = match.createParserWithMatch();
            if (forceClosing) initializingParser.enable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
            // Upstream detection binds with the matched reader, not this detector.
            // Its protected binding method retains managed array/config semantics.
            SequenceReader matched = new SequenceReader(match.getReader());
            return matched._bindAndReadValues(initializingParser);
        }

        private void transferParser() {
            initializingParser = null;
        }

        @Override public void close() throws IOException {
            JsonParser parser = initializingParser;
            initializingParser = null;
            if (parser != null) parser.close();
        }
    }

    /** Uses Jackson's supported 2.19 copy constructor; no iteration engine is replaced. */
    private static final class OwnedValues<V> extends MappingIterator<V> {
        private Closeable input;

        private OwnedValues(MappingIterator<V> values, Closeable input) {
            super(values);
            this.input = input;
        }

        @Override public boolean hasNextValue() throws IOException {
            try {
                boolean more = super.hasNextValue();
                if (!more) close();
                return more;
            } catch (IOException | RuntimeException | Error failure) {
                if (_state == STATE_CLOSED) {
                    Closeable owned = input;
                    input = null;
                    if (owned != null) closeAfterFailure(owned, failure);
                }
                throw failure;
            }
        }

        @Override public void close() throws IOException {
            Closeable owned = input;
            input = null;
            try (owned) {
                super.close();
            }
        }
    }
}

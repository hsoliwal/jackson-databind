/*
 * Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package jdk.m3.jackson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Jackson ObjectMapper with indexed M3 file and NIO input boundaries.
 *
 * <p>The upstream mapper remains the authority for binding, annotations, modules,
 * coercion, duplicate fields and errors. Existing String APIs remain unchanged;
 * the M3JDK runtime owns their internal M3String representation. This class adds
 * no string pool, JSON parser, reflection engine or retained text payload.
 * Configure a mapper before concurrent use, as required by ObjectMapper.
 *
 * <p>Methods accepting M3File open a forward reader over its canonical atom
 * references. Methods accepting Path open a byte stream so Jackson retains its
 * encoding detection. These methods own and close only the streams they open,
 * including when AUTO_CLOSE_SOURCE or AUTO_CLOSE_TARGET is disabled.
 */
public class M3ObjectMapper extends ObjectMapper {
    private static final long serialVersionUID = 1L;

    public M3ObjectMapper() { super(); }

    public M3ObjectMapper(JsonFactory factory) { super(factory); }

    protected M3ObjectMapper(M3ObjectMapper source) { super(source); }

    protected M3ObjectMapper(M3ObjectMapper source, JsonFactory factory) {
        super(source, factory);
    }

    /** Retains Jackson's isolated-copy semantics and this mapper's added APIs. */
    @Override public M3ObjectMapper copy() {
        _checkInvalidCopy(M3ObjectMapper.class);
        return new M3ObjectMapper(this);
    }

    @Override public M3ObjectMapper copyWith(JsonFactory factory) {
        _checkInvalidCopy(M3ObjectMapper.class);
        return new M3ObjectMapper(this, factory);
    }

    public <T> T readValue(M3File source, Class<T> type) throws IOException {
        Objects.requireNonNull(type, "type");
        try (var reader = Objects.requireNonNull(source, "source").reader()) {
            return super.readValue(reader, type);
        }
    }

    public <T> T readValue(M3File source, TypeReference<T> type) throws IOException {
        Objects.requireNonNull(type, "type");
        try (var reader = Objects.requireNonNull(source, "source").reader()) {
            return super.readValue(reader, type);
        }
    }

    public <T> T readValue(M3File source, JavaType type) throws IOException {
        Objects.requireNonNull(type, "type");
        try (var reader = Objects.requireNonNull(source, "source").reader()) {
            return super.readValue(reader, type);
        }
    }

    public JsonNode readTree(M3File source) throws IOException {
        try (var reader = Objects.requireNonNull(source, "source").reader()) {
            return super.readTree(reader);
        }
    }

    public <T> T readValue(Path source, Class<T> type) throws IOException {
        Objects.requireNonNull(type, "type");
        try (var stream = Files.newInputStream(Objects.requireNonNull(source, "source"))) {
            return super.readValue(stream, type);
        }
    }

    public <T> T readValue(Path source, TypeReference<T> type) throws IOException {
        Objects.requireNonNull(type, "type");
        try (var stream = Files.newInputStream(Objects.requireNonNull(source, "source"))) {
            return super.readValue(stream, type);
        }
    }

    public <T> T readValue(Path source, JavaType type) throws IOException {
        Objects.requireNonNull(type, "type");
        try (var stream = Files.newInputStream(Objects.requireNonNull(source, "source"))) {
            return super.readValue(stream, type);
        }
    }

    public JsonNode readTree(Path source) throws IOException {
        try (var stream = Files.newInputStream(Objects.requireNonNull(source, "source"))) {
            return super.readTree(stream);
        }
    }

    /** Reads an owned indexed sequence; close the iterator when stopping early. */
    public <T> MappingIterator<T> readValues(M3File source, Class<T> type) throws IOException {
        return M3Binding.readSequence(readerFor(Objects.requireNonNull(type, "type")), source);
    }

    public <T> MappingIterator<T> readValues(M3File source, TypeReference<T> type)
            throws IOException {
        return M3Binding.readSequence(readerFor(Objects.requireNonNull(type, "type")), source);
    }

    public <T> MappingIterator<T> readValues(M3File source, JavaType type) throws IOException {
        return M3Binding.readSequence(readerFor(Objects.requireNonNull(type, "type")), source);
    }

    /** Reads an owned byte-stream sequence with Jackson's normal encoding detection. */
    public <T> MappingIterator<T> readValues(Path source, Class<T> type) throws IOException {
        return M3Binding.readSequence(readerFor(Objects.requireNonNull(type, "type")), source);
    }

    public <T> MappingIterator<T> readValues(Path source, TypeReference<T> type)
            throws IOException {
        return M3Binding.readSequence(readerFor(Objects.requireNonNull(type, "type")), source);
    }

    public <T> MappingIterator<T> readValues(Path source, JavaType type) throws IOException {
        return M3Binding.readSequence(readerFor(Objects.requireNonNull(type, "type")), source);
    }

    /** Serializes to indexed String chunks, without flattening the document. */
    public M3File writeValueAsM3File(Object value) throws IOException {
        return writeValueAsM3File(value, Integer.MAX_VALUE);
    }

    /** Serializes to indexed output with an explicit UTF-16 length limit. */
    public M3File writeValueAsM3File(Object value, int maxUtf16Length) throws IOException {
        try (M3File.Writer output = M3File.newWriter(maxUtf16Length)) {
            super.writeValue(output, value);
            return output.finish();
        }
    }

    /** Writes through the Path provider, with normal create/truncate semantics. */
    public void writeValue(Path destination, Object value) throws IOException {
        try (var stream = Files.newOutputStream(Objects.requireNonNull(destination, "destination"))) {
            super.writeValue(stream, value);
        }
    }

    /** Prepare Jackson's existing immutable typed reader and writer once. */
    public <T> M3Binding<T> prepare(Class<T> type) {
        return prepare(getTypeFactory().constructType(Objects.requireNonNull(type, "type")));
    }

    public <T> M3Binding<T> prepare(TypeReference<T> type) {
        return prepare(getTypeFactory().constructType(Objects.requireNonNull(type, "type")));
    }

    public <T> M3Binding<T> prepare(JavaType type) {
        Objects.requireNonNull(type, "type");
        return new M3Binding<>(type, readerFor(type), writerFor(type));
    }
}

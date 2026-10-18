// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors.
// Source: hsoliwal/com.synexia
// Commit: 50f48cbbe11f17fb8dc209bac3812f8940a1e267
// Path: synexia-indexstring/src/main/java/com/synexia/indexstring/file/MIndexFileTokenStore.java
// Git blob: 24af695903f1c62d353ce7843ac45267086a6801
// Adaptation: package/class mapping only; the adaptive storage logic is unchanged.
// These primitive arrays hold occurrence IDs, never text payloads.
package jdk.m3.jackson;

import java.util.Arrays;
import java.util.Objects;

/** Width-adaptive immutable primitive storage for file-local lexicon handles. */
sealed interface M3FileTokenStore
    permits M3FileTokenStore.ByteStore,
        M3FileTokenStore.ShortStore,
        M3FileTokenStore.IntStore {
  int size();

  int get(int index);

  int widthBytes();

  long retainedPayloadBytes();

  static M3FileTokenStore freeze(int[] values, int size, int lexiconSize) {
    Objects.requireNonNull(values, "values");
    Objects.checkFromIndexSize(0, size, values.length);
    if (lexiconSize < 0) throw new IllegalArgumentException("negative lexicon size");

    if (lexiconSize <= 1 << Byte.SIZE) {
      byte[] packed = new byte[size];
      for (int i = 0; i < size; i++) packed[i] = (byte) values[i];
      return new ByteStore(packed);
    }
    if (lexiconSize <= 1 << Short.SIZE) {
      short[] packed = new short[size];
      for (int i = 0; i < size; i++) packed[i] = (short) values[i];
      return new ShortStore(packed);
    }
    return new IntStore(Arrays.copyOf(values, size));
  }

  record ByteStore(byte[] values) implements M3FileTokenStore {
    public ByteStore {
      values = Objects.requireNonNull(values, "values");
    }

    @Override
    public int size() {
      return values.length;
    }

    @Override
    public int get(int index) {
      return Byte.toUnsignedInt(values[Objects.checkIndex(index, values.length)]);
    }

    @Override
    public int widthBytes() {
      return Byte.BYTES;
    }

    @Override
    public long retainedPayloadBytes() {
      return values.length;
    }
  }

  record ShortStore(short[] values) implements M3FileTokenStore {
    public ShortStore {
      values = Objects.requireNonNull(values, "values");
    }

    @Override
    public int size() {
      return values.length;
    }

    @Override
    public int get(int index) {
      return Short.toUnsignedInt(values[Objects.checkIndex(index, values.length)]);
    }

    @Override
    public int widthBytes() {
      return Short.BYTES;
    }

    @Override
    public long retainedPayloadBytes() {
      return (long) Short.BYTES * values.length;
    }
  }

  record IntStore(int[] values) implements M3FileTokenStore {
    public IntStore {
      values = Objects.requireNonNull(values, "values");
    }

    @Override
    public int size() {
      return values.length;
    }

    @Override
    public int get(int index) {
      return values[Objects.checkIndex(index, values.length)];
    }

    @Override
    public int widthBytes() {
      return Integer.BYTES;
    }

    @Override
    public long retainedPayloadBytes() {
      return (long) Integer.BYTES * values.length;
    }
  }
}


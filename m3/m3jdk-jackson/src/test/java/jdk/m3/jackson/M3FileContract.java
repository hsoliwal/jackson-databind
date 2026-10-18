// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Hitesh Soliwal and contributors.
package jdk.m3.jackson;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Standalone contract proof; assertions are active without the VM's -ea option. */
public final class M3FileContract {
    private static int checks;

    private M3FileContract() { }

    public static void main(String[] args) throws Exception {
        sourceIndexAndReferenceOwnership();
        emptySourcesAndValidation();
        slicesAndUtf16Seams();
        readerContracts();
        adaptiveWidthBoundaries();
        maximumLogicalLengthWithoutMaterialization();
        randomizedReaderDifferential();
        System.out.println("M3_FILE_CONTRACT_PASS checks=" + checks);
    }

    private static void sourceIndexAndReferenceOwnership() throws Exception {
        String first = new String(new char[] {'x', 'y'});
        String equalButDistinct = new String(new char[] {'x', 'y'});
        check(first != equalButDistinct, "fixture distinct references");
        String[] pieces = {"", first, "\ud83d", "\ude00", equalButDistinct, "", "!"};
        M3File file = M3File.of(pieces);
        pieces[1] = "caller mutation";
        text(file, "xy\ud83d\ude00xy!");
        eq(7, file.sourceTokenCount(), "original occurrence count includes empties");
        eq(5, file.sourceUniqueTokenCount(), "distinct atom spelling count");
        eq(1, file.sourceTokenWidthBytes(), "byte occurrence lane");
        int[] offsets = {0, 0, 2, 3, 4, 6, 6, 7};
        for (int i = 0; i < offsets.length; i++) {
            eq(offsets[i], file.sourceUtf16Offset(i), "source UTF-16 prefix");
        }
        check(file.sourceTokenAt(1) == first, "first supplied reference is retained");
        check(file.sourceTokenAt(4) == first, "equal reference uses the first local atom");
        eq(file.sourceLocalHandleAt(1), file.sourceLocalHandleAt(4), "equal atom handle");
        eq(39L, file.sourceRetainedPrimitiveBytes(), "primitive payload excludes object overhead");

        List<String> mutable = new ArrayList<>(List.of("a", "", "b"));
        M3File listFile = M3File.of(mutable);
        mutable.set(0, "changed");
        mutable.clear();
        text(listFile, "ab");
        check(M3File.of(first).toString() == first, "whole single atom needs no String copy");

        M3File collisions = M3File.of("Aa", "BB", "Aa");
        check("Aa".hashCode() == "BB".hashCode(), "hash collision fixture");
        eq(2, collisions.sourceUniqueTokenCount(), "hash collision exact verification");
        check(collisions.sourceLocalHandleAt(0) != collisions.sourceLocalHandleAt(1),
                "unequal colliding Strings have different handles");
        text(collisions, "AaBBAa");
    }

    private static void emptySourcesAndValidation() throws Exception {
        M3File empty = M3File.of();
        eq(0, empty.length(), "empty source length");
        eq(0, empty.sourceTokenCount(), "empty source tokens");
        eq(0, empty.sourceUniqueTokenCount(), "empty source atoms");
        eq(1, empty.sourceTokenWidthBytes(), "empty source adaptive width");
        eq(0, empty.sourceUtf16Offset(0), "empty terminal boundary");
        eq(4L, empty.sourceRetainedPrimitiveBytes(), "empty prefix sentinel");
        text(empty, "");
        try (Reader reader = empty.reader()) {
            eq(0, reader.read(new char[2], 1, 0), "zero-length read at empty EOF");
            eq(-1, reader.read(), "empty single read EOF");
            eq(-1, reader.read(new char[2]), "empty bulk read EOF");
            eq(0L, reader.skip(Long.MAX_VALUE), "empty skip");
            reader.mark(0);
            reader.reset();
            check(reader.ready(), "empty immutable reader cannot block");
        }
        M3File emptyPieces = M3File.of("", "", "");
        eq(3, emptyPieces.sourceTokenCount(), "empty pieces retained as occurrences");
        eq(1, emptyPieces.sourceUniqueTokenCount(), "empty piece local deduplication");
        for (int i = 0; i <= 3; i++) eq(0, emptyPieces.sourceUtf16Offset(i), "empty piece prefix");
        try (Reader reader = emptyPieces.reader()) {
            eq(-1, reader.read(), "empty pieces read EOF");
        }
        expect(NullPointerException.class, () -> M3File.of((String[]) null));
        expect(NullPointerException.class, () -> M3File.of((List<String>) null));
        expect(NullPointerException.class, () -> M3File.of("a", null));
        expect(NullPointerException.class, () -> M3File.of(Arrays.asList("a", null)));
        expect(IndexOutOfBoundsException.class, () -> empty.charAt(0));
        expect(IndexOutOfBoundsException.class, () -> empty.sourceTokenAt(0));
        expect(IndexOutOfBoundsException.class, () -> empty.sourceUtf16Offset(1));
    }

    private static void slicesAndUtf16Seams() throws Exception {
        M3File source = M3File.of("", "a\ud83d", "", "\ude00b", "\ud800", "x\udc00", "");
        String expected = "a\ud83d\ude00b\ud800x\udc00";
        text(source, expected);
        check(source.subSequence(0, source.length()) == source, "full view preserves identity");
        for (int from = 0; from <= expected.length(); from++) {
            for (int to = from; to <= expected.length(); to++) {
                M3File slice = source.slice(from, to);
                text(slice, expected.substring(from, to));
                eq(from, slice.sourceStart(), "slice source start");
                eq(to, slice.sourceEnd(), "slice source end");
                eq(source.sourceTokenCount(), slice.sourceTokenCount(), "slice shares original index");
                check(slice.sourceTokenAt(1) == source.sourceTokenAt(1), "slice retains original atom");
                eq(source.sourceRetainedPrimitiveBytes(), slice.sourceRetainedPrimitiveBytes(),
                        "slice shares primitive index");
                try (Reader actual = slice.reader(); Reader oracle = new StringReader(expected.substring(from, to))) {
                    for (int width : new int[] {0, 1, 2, 0, 8, 0}) {
                        char[] left = new char[width + 4];
                        char[] right = new char[width + 4];
                        Arrays.fill(left, '\uffff');
                        Arrays.fill(right, '\uffff');
                        eq(oracle.read(right, 2, width), actual.read(left, 2, width), "slice Reader count");
                        check(Arrays.equals(left, right), "slice Reader UTF-16 and untouched boundaries");
                    }
                }
                if (to - from > 1) {
                    M3File nested = slice.subSequence(1, to - from);
                    eq(from + 1, nested.sourceStart(), "nested source coordinate");
                    text(nested, expected.substring(from + 1, to));
                }
            }
        }
        expect(IndexOutOfBoundsException.class, () -> source.charAt(-1));
        expect(IndexOutOfBoundsException.class, () -> source.charAt(source.length()));
        expect(IndexOutOfBoundsException.class, () -> source.subSequence(-1, 0));
        expect(IndexOutOfBoundsException.class, () -> source.subSequence(2, 1));
        expect(IndexOutOfBoundsException.class, () -> source.subSequence(0, source.length() + 1));
        expect(IndexOutOfBoundsException.class, () -> source.sourceLocalHandleAt(-1));
        expect(IndexOutOfBoundsException.class, () -> source.sourceLocalHandleAt(source.sourceTokenCount()));
        expect(IndexOutOfBoundsException.class, () -> source.sourceUtf16Offset(-1));
        expect(IndexOutOfBoundsException.class, () -> source.sourceUtf16Offset(source.sourceTokenCount() + 1));
    }

    private static void readerContracts() throws Exception {
        M3File source = M3File.of("", "a", "", "\ud83d", "", "\ude00", "bc", "");
        try (Reader reader = source.reader(); Reader independent = source.reader()) {
            check(reader.markSupported(), "immutable reader supports marks");
            eq((int) 'a', reader.read(), "first read");
            eq((int) 'a', independent.read(), "independent cursor");
            reader.reset();
            eq((int) 'a', reader.read(), "reset before first mark returns to start");
            reader.mark(0);
            eq((int) '\ud83d', reader.read(), "high surrogate");
            eq((int) '\ude00', reader.read(), "low surrogate in a later atom");
            eq(2L, reader.skip(Long.MAX_VALUE), "skip clamps to view remainder");
            eq(-1, reader.read(), "EOF after skip");
            eq(0, reader.read(new char[1], 1, 0), "zero-length valid read at EOF");
            eq(0L, reader.skip(1), "skip at EOF");
            check(reader.ready(), "open EOF ready");
            reader.reset();
            eq((int) '\ud83d', reader.read(), "mark remains valid beyond read-ahead limit");
            eq(0L, reader.skip(0), "zero skip");
            eq((int) '\ude00', reader.read(), "zero skip preserves cursor");
            expect(IllegalArgumentException.class, () -> reader.skip(-1));
            expect(IllegalArgumentException.class, () -> reader.mark(-1));
            expect(NullPointerException.class, () -> reader.read(null, 0, 0));
            expect(IndexOutOfBoundsException.class, () -> reader.read(new char[3], -1, 1));
            expect(IndexOutOfBoundsException.class, () -> reader.read(new char[3], 0, -1));
            expect(IndexOutOfBoundsException.class, () -> reader.read(new char[3], 2, 2));
            expect(IndexOutOfBoundsException.class, () -> reader.read(new char[3], Integer.MAX_VALUE, 1));
            CharBuffer target = CharBuffer.allocate(4);
            target.position(1);
            target.limit(3);
            eq(2, reader.read(target), "CharBuffer count");
            eq('b', target.get(1), "CharBuffer first unit");
            eq('c', target.get(2), "CharBuffer second unit");
            eq(0, reader.read(target), "zero remaining CharBuffer at EOF");
            eq(-1, reader.read(), "EOF remains after zero-length CharBuffer read");
        }
        try (Reader reader = source.reader()) {
            StringWriter out = new StringWriter();
            eq(5L, reader.transferTo(out), "transfer count");
            eq("a\ud83d\ude00bc", out.toString(), "transfer preserves split surrogate");
        }
        Reader closed = source.reader();
        closed.close();
        closed.close();
        check(closed.markSupported(), "mark support remains a capability after close");
        expect(IOException.class, closed::read);
        expect(IOException.class, () -> closed.read(new char[0], 0, 0));
        expect(IOException.class, () -> closed.read(CharBuffer.allocate(0)));
        expect(IOException.class, () -> closed.skip(0));
        expect(IOException.class, closed::ready);
        expect(IOException.class, () -> closed.mark(0));
        expect(IOException.class, closed::reset);
        text(source, "a\ud83d\ude00bc");
    }

    private static void adaptiveWidthBoundaries() {
        for (int unique : new int[] {255, 256, 257, 65535, 65536, 65537}) {
            String[] pieces = new String[unique + 1];
            for (int i = 0; i < unique; i++) pieces[i] = Integer.toString(i);
            pieces[unique] = new String(pieces[unique - 1].toCharArray());
            M3File file = M3File.of(pieces);
            int expectedWidth = unique <= 256 ? 1 : unique <= 65536 ? 2 : 4;
            eq(expectedWidth, file.sourceTokenWidthBytes(), "adaptive width at " + unique);
            eq(unique, file.sourceUniqueTokenCount(), "unique count at " + unique);
            eq(unique + 1, file.sourceTokenCount(), "occurrence count at " + unique);
            for (int index : new int[] {0, Math.min(unique - 1, 255), Math.min(unique - 1, 256), unique - 1}) {
                eq(index, file.sourceLocalHandleAt(index), "unsigned occurrence at " + index);
                check(file.sourceTokenAt(index) == pieces[index], "source atom reference at width boundary");
            }
            eq(unique - 1, file.sourceLocalHandleAt(unique), "repeated final occurrence");
            check(file.sourceTokenAt(unique) == pieces[unique - 1], "repeated final reference deduplicated");
            eq((long) (unique + 1) * expectedWidth + (long) (unique + 2) * Integer.BYTES,
                    file.sourceRetainedPrimitiveBytes(), "exact primitive width accounting");
            eq('0', file.charAt(0), "first source character at width boundary");
            String last = pieces[unique - 1];
            eq(last.charAt(last.length() - 1), file.charAt(file.length() - 1), "final source character");
        }
    }

    private static void maximumLogicalLengthWithoutMaterialization() throws Exception {
        String largeAtom = "z".repeat(1 << 20);
        String[] pieces = new String[2048];
        Arrays.fill(pieces, largeAtom);
        pieces[pieces.length - 1] = largeAtom.substring(1);
        M3File maximum = M3File.of(pieces);
        eq(Integer.MAX_VALUE, maximum.length(), "maximum CharSequence length");
        eq(Integer.MAX_VALUE, maximum.sourceEnd(), "maximum source end");
        eq(Integer.MAX_VALUE, maximum.sourceUtf16Offset(pieces.length), "maximum terminal prefix");
        eq('z', maximum.charAt(Integer.MAX_VALUE - 1), "last possible code unit");
        text(maximum.slice(Integer.MAX_VALUE - 2, Integer.MAX_VALUE), "zz");
        try (Reader reader = maximum.reader()) {
            eq((long) Integer.MAX_VALUE, reader.skip(Long.MAX_VALUE), "large skip does not overflow");
            eq(-1, reader.read(), "EOF at maximum length");
            eq(0, reader.read(new char[0]), "zero-length read at maximum EOF");
            reader.reset();
            eq((int) 'z', reader.read(), "reset after maximum skip");
        }
        pieces[pieces.length - 1] = largeAtom;
        expect(IllegalArgumentException.class, () -> M3File.of(pieces));
    }

    private static void randomizedReaderDifferential() throws Exception {
        Random random = new Random(0x4d3346494c45L);
        String[] vocabulary = {"", "a", "bb", "\r", "\n", "\u0000", "\ud83d", "\ude00", "\ud800x", "\u00e9"};
        for (int trial = 0; trial < 150; trial++) {
            String[] pieces = new String[random.nextInt(40)];
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < pieces.length; i++) {
                pieces[i] = vocabulary[random.nextInt(vocabulary.length)];
                joined.append(pieces[i]);
            }
            String value = joined.toString();
            int from = random.nextInt(value.length() + 1);
            int to = from + random.nextInt(value.length() - from + 1);
            M3File view = M3File.of(pieces).subSequence(from, to);
            String expected = value.substring(from, to);
            text(view, expected);
            try (Reader actual = view.reader(); Reader oracle = new StringReader(expected)) {
                for (int step = 0; step < 120; step++) {
                    switch (random.nextInt(7)) {
                        case 0 -> eq(oracle.read(), actual.read(), "random single read");
                        case 1 -> {
                            int count = random.nextInt(9);
                            char[] left = new char[12];
                            char[] right = new char[12];
                            Arrays.fill(left, '\uffff');
                            Arrays.fill(right, '\uffff');
                            eq(oracle.read(right, 2, count), actual.read(left, 2, count), "random bulk count");
                            check(Arrays.equals(left, right), "random bulk content and boundaries");
                        }
                        case 2 -> {
                            long count = random.nextInt(13);
                            eq(oracle.skip(count), actual.skip(count), "random forward skip");
                        }
                        case 3 -> {
                            int limit = random.nextInt(4);
                            oracle.mark(limit);
                            actual.mark(limit);
                        }
                        case 4 -> {
                            oracle.reset();
                            actual.reset();
                        }
                        case 5 -> eq(oracle.ready(), actual.ready(), "random ready");
                        default -> {
                            int count = random.nextInt(9);
                            CharBuffer left = CharBuffer.allocate(12);
                            CharBuffer right = CharBuffer.allocate(12);
                            left.position(2).limit(2 + count);
                            right.position(2).limit(2 + count);
                            eq(oracle.read(right), actual.read(left), "random CharBuffer count");
                            check(Arrays.equals(left.array(), right.array()), "random CharBuffer content");
                        }
                    }
                }
            }
        }
    }

    private static void text(M3File file, String expected) {
        eq(expected.length(), file.length(), "view length");
        eq(expected, file.toString(), "view spelling");
        for (int i = 0; i < expected.length(); i++) eq(expected.charAt(i), file.charAt(i), "view code unit");
        check(Arrays.equals(expected.chars().toArray(), file.chars().toArray()), "CharSequence chars");
        check(Arrays.equals(expected.codePoints().toArray(), file.codePoints().toArray()), "CharSequence code points");
    }

    private static void eq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> expected, Checked action) throws Exception {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Expected " + expected.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getName());
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}

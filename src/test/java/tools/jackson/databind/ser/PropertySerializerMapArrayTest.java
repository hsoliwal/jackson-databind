package tools.jackson.databind.ser;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.impl.PropertySerializerMap;
import tools.jackson.databind.ser.std.ToStringSerializer;

import static org.junit.jupiter.api.Assertions.*;

class PropertySerializerMapArrayTest
{
    private static final Class<?>[] TYPES = {
        String.class, Integer.class, Long.class, Double.class,
        Float.class, Short.class, Byte.class, Character.class, Boolean.class
    };

    @Test
    void allSizesKeepEarlierSnapshotsAndPayloadIdentities() {
        ValueSerializer<Object>[] serializers = serializers(9);
        PropertySerializerMap map = PropertySerializerMap.emptyForProperties();
        List<PropertySerializerMap> snapshots = new ArrayList<>();
        snapshots.add(map);
        for (int i = 0; i < 8; ++i) {
            map = map.newWith(TYPES[i], serializers[i]);
            snapshots.add(map);
        }
        for (int size = 0; size <= 8; ++size) {
            for (int i = 0; i < TYPES.length; ++i) {
                assertSame(i < size ? serializers[i] : null,
                        snapshots.get(size).serializerFor(TYPES[i]));
            }
        }
    }

    @Test
    void fullPropertyMapKeepsItsIdentityAndStillReturnsRequestedSerializer() {
        ValueSerializer<Object>[] serializers = serializers(9);
        PropertySerializerMap map = populated(false, serializers, 8);
        assertSame(map, map.newWith(TYPES[8], serializers[8]));
        PropertySerializerMap.SerializerAndMapResult result =
                map.addSerializer(TYPES[8], serializers[8]);
        assertSame(serializers[8], result.serializer);
        assertSame(map, result.map);
        assertNull(map.serializerFor(TYPES[8]));
        assertNotSame(result, map.addSerializer(TYPES[8], serializers[8]));
    }

    @Test
    void fullRootMapResetsWithoutChangingTheOldGeneration() {
        ValueSerializer<Object>[] serializers = serializers(9);
        PropertySerializerMap old = populated(true, serializers, 8);
        PropertySerializerMap reset = old.newWith(TYPES[8], serializers[8]);
        assertSame(serializers[8], reset.serializerFor(TYPES[8]));
        assertNull(old.serializerFor(TYPES[8]));
        for (int i = 0; i < 8; ++i) {
            assertSame(serializers[i], old.serializerFor(TYPES[i]));
            assertNull(reset.serializerFor(TYPES[i]));
        }
        PropertySerializerMap grown = reset.newWith(TYPES[0], serializers[0])
                .newWith(TYPES[1], serializers[1]);
        assertSame(serializers[8], grown.serializerFor(TYPES[8]));
        assertSame(serializers[0], grown.serializerFor(TYPES[0]));
        assertSame(serializers[1], grown.serializerFor(TYPES[1]));
        assertNull(reset.serializerFor(TYPES[0]));
    }

    @Test
    void duplicateClassesKeepTheOriginalFirstThreeThenNewestTailPriority() {
        ValueSerializer<Object>[] serializers = serializers(8);
        for (int length = 3; length <= 8; ++length) {
            for (int first = 0; first < length; ++first) {
                for (int second = first + 1; second < length; ++second) {
                    PropertySerializerMap map = PropertySerializerMap.emptyForProperties();
                    for (int i = 0; i < length; ++i) {
                        Class<?> type = (i == first || i == second) ? Boolean.class : TYPES[i];
                        map = map.newWith(type, serializers[i]);
                    }
                    int expected = first < 3 ? first : second;
                    assertSame(serializers[expected], map.serializerFor(Boolean.class),
                            "length=" + length + ", first=" + first + ", second=" + second);
                }
            }
        }
    }

    @Test
    void nullTypesAndNullSerializerMatchesKeepTheirPriority() {
        ValueSerializer<Object>[] serializers = serializers(8);
        PropertySerializerMap map = PropertySerializerMap.emptyForProperties()
                .newWith(null, null)
                .newWith(String.class, serializers[1])
                .newWith(null, serializers[2])
                .newWith(Integer.class, serializers[3]);
        assertNull(map.serializerFor(null));
        assertSame(serializers[1], map.serializerFor(String.class));
        assertSame(serializers[3], map.serializerFor(Integer.class));

        map = populated(false, serializers, 3)
                .newWith(null, serializers[3])
                .newWith(null, null);
        assertNull(map.serializerFor(null));
        PropertySerializerMap next = map.newWith(null, serializers[5]);
        assertSame(serializers[5], next.serializerFor(null));
        assertNull(map.serializerFor(null));
    }

    @Test
    void siblingGrowthKeepsIndependentImmutableGenerations() {
        ValueSerializer<Object>[] serializers = serializers(6);
        PropertySerializerMap parent = populated(false, serializers, 3);
        PropertySerializerMap left = parent.newWith(Double.class, serializers[3]);
        PropertySerializerMap right = parent.newWith(Float.class, serializers[4]);
        PropertySerializerMap next = left.newWith(Short.class, serializers[5]);
        assertNull(parent.serializerFor(Double.class));
        assertNull(parent.serializerFor(Float.class));
        assertNull(left.serializerFor(Float.class));
        assertNull(right.serializerFor(Double.class));
        assertNull(left.serializerFor(Short.class));
        assertSame(serializers[3], next.serializerFor(Double.class));
        assertSame(serializers[5], next.serializerFor(Short.class));
        for (int i = 0; i < 3; ++i) {
            assertSame(serializers[i], next.serializerFor(TYPES[i]));
            assertSame(serializers[i], right.serializerFor(TYPES[i]));
        }
    }

    @Test
    void multiMapRetainsOnlySeparateTypeAndSerializerArrays() throws Exception {
        for (int size = 3; size <= 8; ++size) {
            PropertySerializerMap map = populated(false, serializers(8), size);
            int typeArrays = 0;
            int serializerArrays = 0;
            for (Field field : map.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertTrue(Modifier.isFinal(field.getModifiers()));
                if (field.getType().isArray()) {
                    field.setAccessible(true);
                    Object[] values = (Object[]) field.get(map);
                    assertEquals(size, values.length);
                    if (field.getType().getComponentType() == Class.class) {
                        ++typeArrays;
                    } else if (field.getType().getComponentType() == ValueSerializer.class) {
                        ++serializerArrays;
                    } else {
                        fail("Retained structural entry array: " + field.getType());
                    }
                }
            }
            assertEquals(1, typeArrays);
            assertEquals(1, serializerArrays);
        }
    }

    @Test
    void concurrentReadersSeeWholeImmutableSnapshots() throws Exception {
        ValueSerializer<Object>[] serializers = serializers(8);
        AtomicReference<PropertySerializerMap> published = new AtomicReference<>(
                populated(true, serializers, 8));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                for (int pass = 0; pass < 20000; ++pass) {
                    PropertySerializerMap snapshot = published.get();
                    for (int i = 0; i < 8; ++i) {
                        assertSame(serializers[i], snapshot.serializerFor(TYPES[i]));
                    }
                }
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        reader.start();
        for (int pass = 0; pass < 1000; ++pass) {
            published.set(populated(true, serializers, 8));
        }
        reader.join();
        if (failure.get() != null) {
            throw new AssertionError("Immutable map publication", failure.get());
        }
    }

    private static PropertySerializerMap populated(boolean root,
            ValueSerializer<Object>[] serializers, int size) {
        PropertySerializerMap map = root ? PropertySerializerMap.emptyForRootValues()
                : PropertySerializerMap.emptyForProperties();
        for (int i = 0; i < size; ++i) {
            map = map.newWith(TYPES[i], serializers[i]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static ValueSerializer<Object>[] serializers(int count) {
        ValueSerializer<Object>[] result = (ValueSerializer<Object>[]) new ValueSerializer<?>[count];
        for (int i = 0; i < count; ++i) {
            result[i] = new ToStringSerializer(Object.class);
        }
        return result;
    }
}

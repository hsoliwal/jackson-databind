/* Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package jdk.m3.jackson.compiler.fixture;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runtime declarations corresponding to attributed compiler contract inputs. */
public final class AstModels {
    private AstModels() { }

    public record Person(@JsonProperty("full_name") String name, int count) { }
    public record Box<T>(T value, List<T> items, Map<String, T> named) { }
    public record ArraysBox<T>(T[] items, T[][] grid) { }
    public record Wild(List<? extends Number> upper, List<? super Integer> lower, List<?> any) { }
    public static class Owner<T> {
        public class Child<U> {
            public T outer;
            public U inner;
        }
    }
    public record Owned(Owner<String>.Child<Integer> item) { }
    public static final class GenericBean<T> {
        private final T value;
        @JsonCreator
        private GenericBean(@JsonProperty("value") T value) { this.value = value; }
        public T getValue() { return value; }
        @Override public boolean equals(Object other) {
            return other instanceof GenericBean<?> bean && Objects.equals(value, bean.value);
        }
        @Override public int hashCode() { return Objects.hashCode(value); }
    }
    public interface MarkerInterface { }
    public enum Choice { ONE, TWO }
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TypeMark { }
    public record Annotated(@TypeMark String value, List<@TypeMark String> names) { }
    public record Dollar$Record(String value) { }
    public record Bounded<T extends Number & Comparable<T>>(T value) { }
    public static final class Deep {
        private Deep() { }
        public record Leaf(String value) { }
    }
    public static Object local() {
        class Local { }
        return new Local();
    }
    public static Object anonymous() { return new Object() { }; }
}

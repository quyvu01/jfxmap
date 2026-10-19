package io.github.quyvu01.jfxmap;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A method reference to a property getter, such as {@code UserDto::getUserName} or {@code User::name}.
 *
 * <p>It is serializable so that the name of the referenced property can be read from the reference, the way FxMap
 * reads it from an expression tree in .NET. Only method references are accepted: a lambda such as
 * {@code x -> x.getName()} carries no property name.
 *
 * @param <T> the type that declares the property
 * @param <R> the type of the property
 */
@FunctionalInterface
public interface PropertyRef<T, R> extends Function<T, R>, Serializable {
}

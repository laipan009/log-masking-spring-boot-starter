package io.github.laipan009.logmasking.core;

import java.io.Serializable;
import java.util.function.Function;

/**
 * Getter reference for {@link LogMasker#field(Object, SerializableFunction)}. {@link Serializable}
 * supplies the lambda's {@code writeReplace} method so its method name can be extracted; values are
 * not serialized for logging.
 */
@FunctionalInterface
public interface SerializableFunction<T, R> extends Function<T, R>, Serializable {
}

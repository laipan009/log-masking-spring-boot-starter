package io.github.laipan009.logmasking.adapter.logback;

import java.io.Serial;

/**
 * A copy of an exception for consumers that need the {@code Throwable} itself, such as Sentry and
 * OpenTelemetry: messages are masked, frames are original, and the first stack trace line keeps the real
 * class name.
 * <p>The copy has its own class: an instance of another exception class cannot be created without
 * calling its constructor, and what that constructor does is unknown. So the masked exception type in
 * Sentry and {@code exception.type} in OpenTelemetry is this class; the real one is in the first stack
 * trace line.
 */
final class MaskedThrowable extends Throwable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String firstLine;

    MaskedThrowable(String firstLine, String message, StackTraceElement[] stackTrace) {
        super(message);
        this.firstLine = firstLine;
        setStackTrace(stackTrace);
    }

    /** Frames come from the original exception; capturing new ones would walk the stack for every copy. */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public String toString() {
        return firstLine;
    }
}

package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.CallerData;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.slf4j.event.KeyValuePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An event that Logback hands to appenders and Java agents already masked.
 * <p>Every accessor consumers read event data through returns masked values: the message text, MDC,
 * key-value pairs and exception messages. Consumers read differently (an encoder renders a line,
 * {@code SocketAppender} serializes fields, Sentry takes arguments and MDC separately, the OpenTelemetry
 * agent captures the message on entry to {@code callAppenders}), but all of them use these getters.
 * <p>The event has no arguments: the message is returned already formatted, and a consumer that formats
 * it from the pattern and arguments itself would receive them unmasked. Only the encoder wrapper sees
 * the original arguments; it uses them to recognize {@code LogMasker.field()} output and not mask it
 * again.
 * <p>The exception is returned with masked messages, and consumers that extract the {@code Throwable}
 * itself, such as Sentry and the OpenTelemetry agent, receive a copy with the same masks (see
 * {@link MaskedThrowableProxy}).
 * <p>Masks are computed lazily and cached, like {@link LoggingEvent} itself does: an event that no
 * appender renders does not pay for them.
 */
final class SourceMaskedEvent extends LoggingEvent {

    /**
     * Frames a logger call passes through on its way to {@code callAppenders}: Logback, the filter itself,
     * SLF4J and its bridges. The caller is the first frame after them.
     */
    private static final String[] LOGGING_FRAMES = {
            "ch.qos.logback.classic.",
            // With the separator; without it, any class whose name starts the same way would match.
            MaskingTurboFilter.class.getName() + "$",
            "org.slf4j.",
            "org.apache.commons.logging.",
            "org.apache.logging.",
            "org.jboss.logging.",
            "java.util.logging.",
    };

    private static final String FILTER_CLASS = MaskingTurboFilter.class.getName();

    private static final String DISPATCH_CLASS = Logger.class.getName();

    private static final String DISPATCH_METHOD = "callAppenders";

    /** The {@code LocationAwareLogger} method wrappers that pass their own class use to enter Logback. */
    private static final String LOCATION_AWARE_METHOD = "log";

    private final LoggerContext context;
    private final String callerBoundary;
    private final Object[] originalArguments;

    private String maskedMessage;
    private Map<String, String> maskedMdc;
    private List<KeyValuePair> maskedKeyValuePairs;
    private IThrowableProxy maskedThrowable;
    private StackTraceElement[] callerData;

    /**
     * @param callerBoundary the class that called the logger, as the fluent API passes it; {@code null}
     *                       when the filter did not receive it
     */
    SourceMaskedEvent(String callerBoundary, Logger logger, Level level, String message, Throwable throwable,
                      Object[] arguments) {
        super(callerBoundary == null ? DISPATCH_CLASS : callerBoundary, logger, level, message, throwable,
                arguments);
        this.context = logger.getLoggerContext();
        this.callerBoundary = callerBoundary;
        // Already without the exception if it was passed as the last argument.
        this.originalArguments = super.getArgumentArray();
    }

    /** Arguments of the logging call, for the encoder wrapper only. */
    Object[] originalArguments() {
        return originalArguments;
    }

    @Override
    public String getFormattedMessage() {
        var result = maskedMessage;
        if (result == null) {
            result = MaskingLogbackSupport.maskMessage(super.getFormattedMessage(), originalArguments);
            maskedMessage = result;
        }
        return result;
    }

    /** The pattern is not exposed: text formatted from it and the arguments would be unmasked. */
    @Override
    public String getMessage() {
        return getFormattedMessage();
    }

    @Override
    public Object[] getArgumentArray() {
        return null;
    }

    @Override
    public Map<String, String> getMDCPropertyMap() {
        var result = maskedMdc;
        if (result == null) {
            result = maskMdc(super.getMDCPropertyMap());
            maskedMdc = result;
        }
        return result;
    }

    @Override
    public Map<String, String> getMdc() {
        return getMDCPropertyMap();
    }

    @Override
    public List<KeyValuePair> getKeyValuePairs() {
        var result = maskedKeyValuePairs;
        if (result == null) {
            var raw = super.getKeyValuePairs();
            if (raw == null) {
                return null;
            }
            result = maskKeyValuePairs(raw);
            maskedKeyValuePairs = result;
        }
        return result;
    }

    @Override
    public IThrowableProxy getThrowableProxy() {
        var result = maskedThrowable;
        if (result == null) {
            result = MaskedThrowableProxy.of(super.getThrowableProxy());
            maskedThrowable = result;
        }
        return result;
    }

    /**
     * Logback finds the caller by the logger class name, but the filter now sits between it and
     * {@code callAppenders}, so the standard search would report the filter as the caller. The search starts
     * at {@code callAppenders} and skips logging frames, which also finds the caller for the JCL, JUL and
     * Log4j bridges whose class is not passed to the filter.
     * <p>Then, as in Logback, frames of the class that passed itself as the boundary are skipped too. The
     * fluent API passes it to the filter, but a {@code LocationAwareLogger} wrapper does not: it is
     * recognized by the call entering Logback through {@code Logger.log}, and the class that called that
     * method becomes the boundary.
     */
    @Override
    public StackTraceElement[] getCallerData() {
        var result = callerData;
        if (result == null) {
            result = extractCallerData(new Throwable().getStackTrace(), callerBoundary,
                    context.getMaxCallerDataDepth(), context.getFrameworkPackages());
            callerData = result;
        }
        return result;
    }

    @Override
    public boolean hasCallerData() {
        return callerData != null;
    }

    @Override
    public void setCallerData(StackTraceElement[] callerData) {
        this.callerData = callerData;
    }

    static StackTraceElement[] extractCallerData(StackTraceElement[] stack, String boundary, int maxDepth,
                                                 List<String> frameworkPackages) {
        var i = 0;
        while (i < stack.length && !isCall(stack[i], DISPATCH_METHOD)) {
            i++;
        }
        if (i == stack.length) {
            return CallerData.EMPTY_CALLER_DATA_ARRAY;
        }
        i = skip(stack, i + 1, null, frameworkPackages);
        if (boundary == null && i < stack.length && isCall(stack[i - 1], LOCATION_AWARE_METHOD)) {
            boundary = stack[i].getClassName();
        }
        if (boundary != null) {
            var start = i;
            while (start < stack.length && !boundary.equals(stack[start].getClassName())) {
                start++;
            }
            if (start < stack.length) {
                i = skip(stack, start, boundary, frameworkPackages);
            }
        }
        var length = Math.min(maxDepth, stack.length - i);
        if (length <= 0) {
            return CallerData.EMPTY_CALLER_DATA_ARRAY;
        }
        var result = new StackTraceElement[length];
        System.arraycopy(stack, i, result, 0, length);
        return result;
    }

    private static boolean isCall(StackTraceElement frame, String method) {
        return DISPATCH_CLASS.equals(frame.getClassName()) && method.equals(frame.getMethodName());
    }

    private static int skip(StackTraceElement[] stack, int from, String boundary, List<String> frameworkPackages) {
        var i = from;
        while (i < stack.length && (stack[i].getClassName().equals(boundary)
                || isLoggingFrame(stack[i].getClassName(), frameworkPackages))) {
            i++;
        }
        return i;
    }

    private static boolean isLoggingFrame(String className, List<String> frameworkPackages) {
        if (className.equals(FILTER_CLASS)) {
            return true;
        }
        for (var prefix : LOGGING_FRAMES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        if (frameworkPackages != null) {
            for (var prefix : frameworkPackages) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The same instance when no value changed: MDC usually holds only traceId and spanId. */
    private static Map<String, String> maskMdc(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        Map<String, String> masked = null;
        for (var entry : raw.entrySet()) {
            var value = entry.getValue();
            var result = MaskingLogbackSupport.maskValue(entry.getKey(), value);
            if (result != value) {
                if (masked == null) {
                    masked = new HashMap<>(raw);
                }
                masked.put(entry.getKey(), result);
            }
        }
        return masked == null ? raw : Collections.unmodifiableMap(masked);
    }

    /**
     * A value that needed no masking stays its own object, so a number in a JSON log stays a number. A
     * changed value becomes the mask string.
     */
    private static List<KeyValuePair> maskKeyValuePairs(List<KeyValuePair> raw) {
        List<KeyValuePair> masked = null;
        for (var i = 0; i < raw.size(); i++) {
            var pair = raw.get(i);
            if (pair.value == null) {
                continue;
            }
            var value = String.valueOf(pair.value);
            var result = MaskingLogbackSupport.maskValue(pair.key, value);
            if (result != value) {
                if (masked == null) {
                    masked = new ArrayList<>(raw);
                }
                masked.set(i, new KeyValuePair(pair.key, result));
            }
        }
        return masked == null ? raw : Collections.unmodifiableList(masked);
    }
}

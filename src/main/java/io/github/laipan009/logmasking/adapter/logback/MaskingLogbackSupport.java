package io.github.laipan009.logmasking.adapter.logback;

import org.slf4j.LoggerFactory;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the {@link LineMasker} used for masking at the source and by encoder wrappers. Logback
 * constructs the turbo filter and encoders outside Spring, so access is static. Before the context is
 * ready, a fallback masks generic default names in startup logs.
 */
public final class MaskingLogbackSupport {

    /** Written to the log in place of text that could not be masked. */
    public static final String SUPPRESSED = "[message suppressed: log masking failed]";

    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

    private static volatile LineMasker lineMasker;

    private MaskingLogbackSupport() {
    }

    /**
     * Installs application-context rules in place of the generic startup fallback.
     */
    public static void useLineMasker(LineMasker masker) {
        lineMasker = masker;
    }

    /**
     * Returns the original string instance when no masking is needed, allowing {@link MaskingEncoder} to
     * reuse the original bytes without transcoding.
     */
    public static String maskLine(String line) {
        return maskLine(line, null);
    }

    /**
     * Masks a line rendered from a logging call; see {@link LineMasker#mask(String, Object[])} for the
     * handling of {@code MaskedField} arguments.
     */
    public static String maskLine(String line, Object[] arguments) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        try {
            return masker().mask(line, arguments);
        } catch (RuntimeException | LinkageError e) {
            return line;
        }
    }

    /**
     * Like {@link #maskLine(String, Object[])}, but a masker failure yields {@link #SUPPRESSED} instead of
     * the original text. An event masked at the source is read by everyone at once, including appenders
     * without an encoder and Java agents, with no second line behind them, so it must not go out raw.
     */
    static String maskMessage(String message, Object[] arguments) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        try {
            return masker().mask(message, arguments);
        } catch (RuntimeException | LinkageError e) {
            reportFailureOnce(e);
            return SUPPRESSED;
        }
    }

    /** Masks a value with a separately known name (MDC, key-value); {@link #SUPPRESSED} on failure. */
    static String maskValue(String name, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            return masker().maskValue(name, value);
        } catch (RuntimeException | LinkageError e) {
            reportFailureOnce(e);
            return SUPPRESSED;
        }
    }

    private static LineMasker masker() {
        var current = lineMasker;
        return current == null ? FallbackHolder.FALLBACK_LINE : current;
    }

    /**
     * The failure line goes through the same logger and is masked again; the flag is set before writing,
     * so a repeated failure on that line does not loop.
     */
    private static void reportFailureOnce(Throwable failure) {
        if (!FAILURE_REPORTED.compareAndSet(false, true)) {
            return;
        }
        try {
            LoggerFactory.getLogger(MaskingLogbackSupport.class).warn("Log masking failed, the message was "
                    + "replaced with {}; further failures are not logged", SUPPRESSED, failure);
        } catch (RuntimeException | LinkageError e) {
            // Logging is broken together with the masker; there is nowhere to report.
        }
    }

    private static final class FallbackHolder {

        private static final MaskingPolicyRegistry DEFAULTS =
                new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()));

        private static final LineMasker FALLBACK_LINE = new LineMasker(DEFAULTS);

        private FallbackHolder() {
        }
    }
}

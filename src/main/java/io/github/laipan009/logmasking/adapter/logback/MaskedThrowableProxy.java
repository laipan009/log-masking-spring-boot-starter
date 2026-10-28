package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.PackagingDataCalculator;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;

/**
 * The event exception as consumers see it: the same class and frames, with masked messages across the
 * whole chain of causes and suppressed exceptions.
 * <p>Extends {@link ThrowableProxy}, not only {@link IThrowableProxy}: Sentry casts the event exception
 * to this class without a check, OpenTelemetry and the Spring Boot {@code StackTracePrinter} with a
 * check, and all of them extract the {@code Throwable} from it. The original {@code Throwable} would
 * return an unmasked {@code getMessage()}, so {@link #getThrowable()} returns a copy, a
 * {@link MaskedThrowable}.
 * <p>A chain with nothing to mask stays original: a copy changes the exception type seen by Sentry and
 * OpenTelemetry, and the original exception discloses nothing beyond the mask. The same applies to part
 * of a chain: a clean cause of a masked exception stays itself. If the chain has a cycle, everything is
 * copied: from an original exception in the clean part, the cycle could lead back to what had to be
 * masked.
 */
final class MaskedThrowableProxy extends ThrowableProxy {

    /** The parent requires a {@code Throwable}; without a stack or causes it copies nothing. */
    private static final Throwable BLANK = new Throwable(null, null, false, false) {
    };

    /** {@code getOverridingMessage()} exists in Logback 1.5.25 but not yet in 1.5.20. */
    private static final boolean OVERRIDING_MESSAGE = hasOverridingMessage();

    /**
     * Whether the exception text is just the class and {@code getMessage()}: {@code toString()} and
     * {@code getLocalizedMessage()} are not overridden. Needed where Logback does not check this itself.
     */
    private static final ClassValue<Boolean> NOMINAL_TEXT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("toString").getDeclaringClass() == Throwable.class
                        && type.getMethod("getLocalizedMessage").getDeclaringClass() == Throwable.class;
            } catch (NoSuchMethodException | SecurityException e) {
                return false;
            }
        }
    };

    private final IThrowableProxy delegate;
    private final String message;
    private final String overridingMessage;
    private final IThrowableProxy cause;
    private final IThrowableProxy[] suppressed;
    private volatile Throwable throwable;

    private MaskedThrowableProxy(IThrowableProxy delegate, String message, String overridingMessage,
                                 IThrowableProxy cause, IThrowableProxy[] suppressed) {
        super(BLANK);
        this.delegate = delegate;
        this.message = message;
        this.overridingMessage = overridingMessage;
        this.cause = cause;
        this.suppressed = suppressed;
    }

    static IThrowableProxy of(IThrowableProxy proxy) {
        if (proxy == null || proxy instanceof MaskedThrowableProxy) {
            return proxy;
        }
        return mask(proxy, !hasCycle(proxy));
    }

    /** Builds a wrapper only if masking changed something, so exceptions without personal data cost nothing. */
    private static IThrowableProxy mask(IThrowableProxy proxy, boolean keepClean) {
        if (proxy == null) {
            return null;
        }
        var message = MaskingLogbackSupport.maskMessage(proxy.getMessage(), null);
        var overridingMessage = OVERRIDING_MESSAGE
                ? MaskingLogbackSupport.maskMessage(proxy.getOverridingMessage(), null) : null;
        var cause = mask(proxy.getCause(), keepClean);
        var suppressed = maskAll(proxy.getSuppressed(), keepClean);
        if (keepClean && changesNothing(proxy, message, overridingMessage, cause, suppressed)) {
            return proxy;
        }
        return new MaskedThrowableProxy(proxy, message, overridingMessage, cause, suppressed);
    }

    /** The same array when no suppressed exception changed. */
    private static IThrowableProxy[] maskAll(IThrowableProxy[] raw, boolean keepClean) {
        if (raw == null || raw.length == 0) {
            return raw;
        }
        IThrowableProxy[] result = null;
        for (var i = 0; i < raw.length; i++) {
            var masked = mask(raw[i], keepClean);
            if (masked != raw[i] && result == null) {
                result = raw.clone();
            }
            if (result != null) {
                result[i] = masked;
            }
        }
        return result == null ? raw : result;
    }

    /** Masking changed nothing here or below, and the {@code Throwable} does not print itself in its own way. */
    private static boolean changesNothing(IThrowableProxy proxy, String message, String overridingMessage,
                                          IThrowableProxy cause, IThrowableProxy[] suppressed) {
        if (!(proxy instanceof ThrowableProxy original) || message != proxy.getMessage()
                || cause != proxy.getCause() || suppressed != proxy.getSuppressed()) {
            return false;
        }
        return OVERRIDING_MESSAGE ? overridingMessage == proxy.getOverridingMessage()
                : NOMINAL_TEXT.get(original.getThrowable().getClass());
    }

    private static boolean hasCycle(IThrowableProxy proxy) {
        if (proxy == null) {
            return false;
        }
        if (proxy.isCyclic() || hasCycle(proxy.getCause())) {
            return true;
        }
        var suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (var each : suppressed) {
                if (hasCycle(each)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasOverridingMessage() {
        try {
            IThrowableProxy.class.getMethod("getOverridingMessage");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** The copy is built only when the exception is actually requested; encoders need only the text. */
    @Override
    public Throwable getThrowable() {
        var result = throwable;
        if (result == null) {
            result = copy();
            throwable = result;
        }
        return result;
    }

    private Throwable copy() {
        var frames = getStackTraceElementProxyArray();
        var stackTrace = new StackTraceElement[frames == null ? 0 : frames.length];
        for (var i = 0; i < stackTrace.length; i++) {
            stackTrace[i] = frames[i].getStackTraceElement();
        }
        var firstLine = overridingMessage != null ? overridingMessage
                : message == null ? getClassName() : getClassName() + ": " + message;
        var copy = new MaskedThrowable(firstLine, message, stackTrace);
        if (cause instanceof ThrowableProxy proxy) {
            copy.initCause(proxy.getThrowable());
        }
        if (suppressed != null) {
            for (var each : suppressed) {
                if (each instanceof ThrowableProxy proxy && proxy.getThrowable() != null) {
                    copy.addSuppressed(proxy.getThrowable());
                }
            }
        }
        return copy;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getOverridingMessage() {
        return overridingMessage;
    }

    @Override
    public String getClassName() {
        return delegate.getClassName();
    }

    @Override
    public StackTraceElementProxy[] getStackTraceElementProxyArray() {
        return delegate.getStackTraceElementProxyArray();
    }

    @Override
    public int getCommonFrames() {
        return delegate.getCommonFrames();
    }

    @Override
    public IThrowableProxy getCause() {
        return cause;
    }

    @Override
    public IThrowableProxy[] getSuppressed() {
        return suppressed;
    }

    @Override
    public boolean isCyclic() {
        return delegate.isCyclic();
    }

    /** Packaging data belongs to the original frames: Logback computes it when the event is created. */
    @Override
    public PackagingDataCalculator getPackagingDataCalculator() {
        return delegate instanceof ThrowableProxy original ? original.getPackagingDataCalculator()
                : super.getPackagingDataCalculator();
    }

    @Override
    public void calculatePackagingData() {
        if (delegate instanceof ThrowableProxy original) {
            original.calculatePackagingData();
        }
    }

    /** Prints frames only, without messages. */
    @Override
    public void fullDump() {
        if (delegate instanceof ThrowableProxy original) {
            original.fullDump();
        }
    }
}

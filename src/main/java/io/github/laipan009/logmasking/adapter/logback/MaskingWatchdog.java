package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.ConfigurationEvent;
import ch.qos.logback.core.spi.ConfigurationEventListener;
import org.slf4j.LoggerFactory;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller.Report;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller.Source;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps the wrappers in place when Logback is rebuilt outside Spring events.
 * <p>Startup, context readiness and a Spring Cloud refresh install the wrappers themselves. Outside
 * them, Logback is rebuilt by {@code scan="true"} in logback.xml, and an appender can be added after
 * startup by code or a library. Masking would then be lost until a restart.
 * <p>Every XML configuration (a {@code scan} reload, the fallback configuration after a parse error, a
 * {@code logback-spring.xml} rebuild on refresh) ends with a {@code *CONFIGURATION_ENDED*} event, and
 * the wrappers are installed on it immediately, on the same thread. Event listeners survive a context
 * {@code reset()}, so subscribing at startup is enough. The event does not cover appenders added by code
 * or Boot's programmatic configuration without XML; a periodic pass handles those. While rebuilding
 * Logback, Spring silences all logging until {@code initialize()} ends, so a line written from the event
 * would be lost: such wrappers accumulate in {@link #takeUnreported()} and the pass after the refresh
 * reports them.
 * <p>A pass without a rebuild changes nothing and costs tens of microseconds for hundreds of loggers,
 * so the periodic pass logs only when something had to be wrapped again: that is the signal that a gap
 * existed.
 */
public final class MaskingWatchdog implements AutoCloseable {

    private static final String THREAD_NAME = "log-masking-watchdog";

    /** Shared: there can be several subscriptions, one per context, but one Logback, and the first one wraps. */
    private static final AtomicInteger UNREPORTED = new AtomicInteger();

    private final Supplier<LoggerContext> context;
    private final ConfigurationEventListener onConfigured = this::onConfigurationEvent;
    private final LoggerContext subscribed;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicBoolean sourceFailureReported = new AtomicBoolean();

    private MaskingWatchdog(Duration interval, Supplier<LoggerContext> context) {
        this.context = context;
        this.subscribed = subscribe();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            this.executor = null;
            return;
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        // Property validation rejects less than a second; without a validator, at least do not fail startup.
        var millis = Math.max(1, interval.toMillis());
        executor.scheduleWithFixedDelay(this::check, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * @param interval zero or negative disables the periodic pass; configuration events remain
     */
    public static MaskingWatchdog start(Duration interval) {
        return new MaskingWatchdog(interval, MaskingWatchdog::globalContext);
    }

    static MaskingWatchdog start(Duration interval, Supplier<LoggerContext> context) {
        return new MaskingWatchdog(interval, context);
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (subscribed != null) {
            try {
                subscribed.removeConfigurationEventListener(onConfigured);
            } catch (LinkageError e) {
                // Old Logback can subscribe but not unsubscribe; the context is closing anyway.
            }
        }
    }

    private LoggerContext subscribe() {
        try {
            var current = context.get();
            if (current != null) {
                current.addConfigurationEventListener(onConfigured);
            }
            return current;
        } catch (RuntimeException | LinkageError e) {
            // Logback without configuration events: the periodic pass remains.
            return null;
        }
    }

    private void onConfigurationEvent(ConfigurationEvent event) {
        // By name, to tolerate Logback versions that lack some of the three "configuration ended" types.
        if (event.getEventType().name().contains("CONFIGURATION_ENDED")) {
            reinstall(report -> {
                var log = LoggerFactory.getLogger(MaskingWatchdog.class);
                if (log.isInfoEnabled()) {
                    log.info("Log masking is attached to {} appender(s){} after Logback was reconfigured",
                            report.wrapped(), report.isMaskedAtSource() ? " and to the event source" : "");
                } else {
                    // Zero wrappers with a re-attached source is still worth reporting after the refresh.
                    UNREPORTED.addAndGet(Math.max(1, report.wrapped()));
                }
            });
        }
    }

    /** Returns how many wrappers events installed while logging was silenced, and resets the counter. */
    public static int takeUnreported() {
        return UNREPORTED.getAndSet(0);
    }

    // An exception from the task cancels all later runs; the check must not die.
    private void check() {
        reinstall(report -> LoggerFactory.getLogger(MaskingWatchdog.class).warn("Log masking re-attached to {} "
                + "appender(s){}: Logback was reconfigured or an appender was added outside of Spring events, "
                + "lines written there since then were not masked", report.wrapped(),
                report.source() == Source.ATTACHED ? " and to the event source" : ""));
    }

    private void reinstall(Consumer<Report> onWrapped) {
        try {
            var current = context.get();
            if (current == null) {
                return;
            }
            var report = MaskingAppenderInstaller.install(current);
            if (report.changed()) {
                onWrapped.accept(report);
            }
            // Also when there was nothing to wrap: changed() is then false while the source is unmasked.
            if (!report.isMaskedAtSource()) {
                reportSourceFailureOnce();
            }
        } catch (RuntimeException | LinkageError e) {
            reportFailureOnce(e);
        }
    }

    /** Once: if every pass fails, masking will not come back, and someone must know. */
    private void reportFailureOnce(Throwable failure) {
        if (!failureReported.compareAndSet(false, true)) {
            return;
        }
        try {
            LoggerFactory.getLogger(MaskingWatchdog.class).warn("Log masking re-attach failed, masking may stay "
                    + "detached until restart; further failures are not logged", failure);
        } catch (RuntimeException | LinkageError e) {
            // Logging is broken together with Logback; there is nowhere to report.
        }
    }

    /** Once: the pass repeats every {@code recheck-interval}, and repeating will not fix the failure. */
    private void reportSourceFailureOnce() {
        if (sourceFailureReported.compareAndSet(false, true)) {
            LoggerFactory.getLogger(MaskingWatchdog.class).warn("Log masking could not be re-attached to the event "
                    + "source: appenders without an encoder and Java agents receive events unmasked, only encoder "
                    + "output is masked; further failures are not logged");
        }
    }

    private static LoggerContext globalContext() {
        return LoggerFactory.getILoggerFactory() instanceof LoggerContext logback ? logback : null;
    }
}

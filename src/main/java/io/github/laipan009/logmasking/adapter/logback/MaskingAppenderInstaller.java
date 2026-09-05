package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.spi.AppenderAttachable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Installs {@link MaskingEncoder} around supported appender encoders. Traverses all loggers and nested
 * {@link AppenderAttachable} appenders, including async wrappers.
 * <p>Installation is idempotent across shared appenders and repeated listener passes, preventing
 * duplicate scanning.
 * <p>Appenders without encoders, such as SocketAppender, SyslogAppender and SMTPAppender, are reported
 * as unsupported so the application can warn at startup.
 */
public final class MaskingAppenderInstaller {

    private MaskingAppenderInstaller() {
    }

    public static Report install(LoggerContext context) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Appender<ILoggingEvent>, Boolean>());
        var report = new Counters();
        for (var logger : context.getLoggerList()) {
            var appenders = logger.iteratorForAppenders();
            while (appenders.hasNext()) {
                visit(appenders.next(), visited, report);
            }
        }
        return report.toReport();
    }

    private static void visit(Appender<ILoggingEvent> appender,
                              Set<Appender<ILoggingEvent>> visited,
                              Counters report) {
        if (appender == null || !visited.add(appender)) {
            return;
        }
        if (appender instanceof AppenderAttachable<?> attachable) {
            // The composite appender uses the same erased event type E as Appender<E>, but the compiler cannot
            // infer this from the hierarchy.
            @SuppressWarnings("unchecked")
            var nested = ((AppenderAttachable<ILoggingEvent>) attachable).iteratorForAppenders();
            while (nested.hasNext()) {
                visit(nested.next(), visited, report);
            }
            return;
        }
        if (!(appender instanceof OutputStreamAppender<ILoggingEvent> streamAppender)) {
            report.unsupported.add(appender.getClass().getName());
            return;
        }
        wrap(streamAppender, report);
    }

    private static void wrap(OutputStreamAppender<ILoggingEvent> appender, Counters report) {
        var encoder = appender.getEncoder();
        if (encoder == null) {
            report.unsupported.add(appender.getClass().getName());
            return;
        }
        if (encoder instanceof MaskingEncoder) {
            report.alreadyWrapped++;
            return;
        }
        var masking = new MaskingEncoder(encoder);
        masking.setContext(appender.getContext());
        masking.start();
        appender.setEncoder(masking);
        report.wrapped++;
    }

    private static final class Counters {

        private final List<String> unsupported = new ArrayList<>();
        private int wrapped;
        private int alreadyWrapped;

        private Report toReport() {
            return new Report(wrapped, alreadyWrapped, List.copyOf(unsupported));
        }
    }

    /**
     * Summarizes installation.
     * @param wrapped appenders wrapped during this pass
     * @param alreadyWrapped appenders previously wrapped
     * @param unsupported class names of appenders without supported encoders
     */
    public record Report(int wrapped, int alreadyWrapped, List<String> unsupported) {

        public int covered() {
            return wrapped + alreadyWrapped;
        }

        public boolean isFullyCovered() {
            return unsupported.isEmpty();
        }
    }
}

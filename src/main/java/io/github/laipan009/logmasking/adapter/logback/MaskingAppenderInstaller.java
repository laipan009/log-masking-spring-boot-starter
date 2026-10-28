package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.spi.AppenderAttachable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Installs masking at the source ({@link MaskingTurboFilter}) and {@link MaskingEncoder} around the
 * encoder of each appender.
 * <p>The source is the main line: the event is masked before appenders and Java agents see it. The
 * encoder wrapper is the second line: it catches what the encoder adds to the event itself and events
 * that bypassed the filter.
 * <p>Traverses all loggers and nested {@link AppenderAttachable} appenders, including async wrappers.
 * <p>Encoders are not limited to {@link OutputStreamAppender}: network and message broker appenders
 * encode events the same way but are outside that hierarchy. Their encoder is found through
 * {@code setEncoder} and a getter or, without a getter, an {@code encoder} field. A composite appender
 * may write the main stream itself and pass only what it failed to send to nested appenders, so its own
 * encoder is wrapped too. The starter does not know concrete appender classes: whatever an appender
 * sends past its encoder is covered by the source.
 * <p>Installation is idempotent across shared appenders and repeated passes, preventing duplicate
 * scanning. Passes run when the environment is prepared, when the context is prepared, after a refresh
 * (twice with bootstrap, since the event also reaches the parent context) and periodically.
 * <p>Appenders without encoders, such as SocketAppender, SyslogAppender and SMTPAppender, are reported:
 * their output relies on the source alone, and the application says so at startup.
 */
public final class MaskingAppenderInstaller {

    private static final ClassValue<Optional<EncoderAccess>> ENCODER_ACCESS = new ClassValue<>() {
        @Override
        protected Optional<EncoderAccess> computeValue(Class<?> type) {
            return EncoderAccess.find(type);
        }
    };

    private MaskingAppenderInstaller() {
    }

    /**
     * Passes can run on different threads (startup, refresh, periodic recheck), and the "already wrapped"
     * check and the encoder swap are not atomic.
     */
    public static synchronized Report install(LoggerContext context) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Appender<ILoggingEvent>, Boolean>());
        var report = new Counters();
        report.source = attachSource(context);
        for (var logger : context.getLoggerList()) {
            var appenders = logger.iteratorForAppenders();
            while (appenders.hasNext()) {
                visit(appenders.next(), visited, report);
            }
        }
        return report.toReport();
    }

    /** Attaches the source in the same pass: a context {@code reset()} removes turbo filters with appenders. */
    private static Source attachSource(LoggerContext context) {
        try {
            return MaskingTurboFilter.attach(context) ? Source.ATTACHED : Source.PRESENT;
        } catch (RuntimeException | LinkageError e) {
            return Source.FAILED;
        }
    }

    private static void visit(Appender<ILoggingEvent> appender,
                              Set<Appender<ILoggingEvent>> visited,
                              Counters report) {
        if (appender == null || !visited.add(appender)) {
            return;
        }
        if (appender instanceof OutputStreamAppender<ILoggingEvent> streamAppender) {
            wrap(streamAppender, streamAppender.getEncoder(), streamAppender::setEncoder, report);
            return;
        }
        var access = ENCODER_ACCESS.get(appender.getClass());
        if (appender instanceof AppenderAttachable<?> attachable) {
            // The composite appender uses the same erased event type E as Appender<E>, but the compiler cannot
            // infer this from the hierarchy.
            @SuppressWarnings("unchecked")
            var nested = ((AppenderAttachable<ILoggingEvent>) attachable).iteratorForAppenders();
            while (nested.hasNext()) {
                visit(nested.next(), visited, report);
            }
            // A composite without an encoder (AsyncAppender) only forwards events and is covered by nested ones.
            access.ifPresent(found -> wrap(appender, found, report));
            return;
        }
        if (access.isEmpty()) {
            report.unsupported.add(appender.getClass().getName());
            return;
        }
        wrap(appender, access.get(), report);
    }

    private static void wrap(Appender<ILoggingEvent> appender, EncoderAccess access, Counters report) {
        try {
            wrap(appender, access.get(appender), encoder -> access.set(appender, encoder), report);
        } catch (IllegalStateException e) {
            report.unsupported.add(appender.getClass().getName());
        }
    }

    private static void wrap(Appender<ILoggingEvent> appender,
                             Encoder<ILoggingEvent> encoder,
                             Consumer<Encoder<ILoggingEvent>> setEncoder,
                             Counters report) {
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
        setEncoder.accept(masking);
        report.wrapped++;
    }

    /** Encoder access outside the {@link OutputStreamAppender} hierarchy: a public setter and a getter or field. */
    private record EncoderAccess(Method setter, Method getter, Field field) {

        static Optional<EncoderAccess> find(Class<?> type) {
            Method setter;
            try {
                setter = type.getMethod("setEncoder", Encoder.class);
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
            try {
                var getter = type.getMethod("getEncoder");
                if (Encoder.class.isAssignableFrom(getter.getReturnType())) {
                    return Optional.of(new EncoderAccess(setter, getter, null));
                }
            } catch (NoSuchMethodException e) {
                // No getter: look for the field.
            }
            for (var current = type; current != null; current = current.getSuperclass()) {
                try {
                    var field = current.getDeclaredField("encoder");
                    if (Encoder.class.isAssignableFrom(field.getType()) && field.trySetAccessible()) {
                        return Optional.of(new EncoderAccess(setter, null, field));
                    }
                    return Optional.empty();
                } catch (NoSuchFieldException e) {
                    // The field is declared higher in the hierarchy.
                }
            }
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        Encoder<ILoggingEvent> get(Appender<ILoggingEvent> appender) {
            try {
                return (Encoder<ILoggingEvent>) (getter != null ? getter.invoke(appender) : field.get(appender));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        void set(Appender<ILoggingEvent> appender, Encoder<ILoggingEvent> encoder) {
            try {
                setter.invoke(appender, encoder);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class Counters {

        private final List<String> unsupported = new ArrayList<>();
        private int wrapped;
        private int alreadyWrapped;
        private Source source = Source.FAILED;

        private Report toReport() {
            return new Report(wrapped, alreadyWrapped, List.copyOf(unsupported), source);
        }
    }

    /** State of masking at the source after a pass. */
    public enum Source {

        /** This pass attached the filter or moved it back to the front of the chain. */
        ATTACHED,

        /** The filter was already first. */
        PRESENT,

        /** The filter could not be attached: events reach appenders and agents unmasked. */
        FAILED
    }

    /**
     * Summarizes installation.
     * @param wrapped appenders wrapped during this pass
     * @param alreadyWrapped appenders previously wrapped
     * @param unsupported class names of appenders without supported encoders
     * @param source masking at the source
     */
    public record Report(int wrapped, int alreadyWrapped, List<String> unsupported, Source source) {

        public int covered() {
            return wrapped + alreadyWrapped;
        }

        /** Every appender writes through a masking encoder. */
        public boolean isFullyCovered() {
            return unsupported.isEmpty();
        }

        /** Events are masked before appenders: appenders without an encoder and agents receive masked data. */
        public boolean isMaskedAtSource() {
            return source != Source.FAILED;
        }

        /** The pass changed something worth logging. */
        public boolean changed() {
            return wrapped > 0 || source == Source.ATTACHED;
        }
    }
}

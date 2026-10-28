package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Masks at the source: the event is masked before anyone sees it.
 * <p>The encoder wrapper covers only what an appender writes through its encoder. Other readers bypass
 * it: appenders without an encoder, Sentry (arguments and MDC separately), and the OpenTelemetry Java
 * agent, which reads the event on entry to {@code Logger.callAppenders}, before any appender. Logback
 * calls turbo filters before it creates the event, on every path: {@code log.info(...)}, the JCL, JUL and
 * Log4j bridges, and the SLF4J fluent API. This filter builds a {@link SourceMaskedEvent} itself, passes
 * it to {@code callAppenders} and replies {@code DENY}, so Logback never creates the original, unmasked
 * event.
 * <p>A {@code DENY} reply ends the chain, so the filter applies the other turbo filters and the level
 * check itself, exactly as Logback would: another filter denies, no event; accepts, the event is logged
 * at any level; stays neutral, the logger level decides. This requires the filter to be first: a filter
 * that replied before it would let the event through unmasked. {@code MaskingAppenderInstaller} keeps it
 * at the front on every pass.
 * <p>{@code isDebugEnabled()} checks arrive without a message and produce no event, so the filter
 * replies {@code NEUTRAL} and does not interfere. {@code log.error(null)} looks the same, for example
 * {@code log.error(e.getMessage())} for an exception without a message: Logback builds such an event
 * itself, and appenders without an encoder and agents receive its MDC unmasked.
 * <p>Not every Logback version passes the SLF4J fluent API through turbo filters: 1.5.25 does, 1.5.20
 * does not. With older Logback such events bypass the filter and only the encoder wrapper masks them.
 */
final class MaskingTurboFilter extends TurboFilter {

    static final String NAME = "log-masking";

    private static final String FQCN = Logger.class.getName();

    private final AtomicBoolean failureReported = new AtomicBoolean();

    MaskingTurboFilter() {
        setName(NAME);
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params,
                              Throwable t) {
        if (format == null && params == null && t == null) {
            return FilterReply.NEUTRAL;
        }
        var reply = alone(logger) ? FilterReply.NEUTRAL
                : decisionOfOthers(logger, filter -> filter.decide(marker, logger, level, format, params, t));
        if (reply == FilterReply.DENY
                || reply == FilterReply.NEUTRAL && !level.isGreaterOrEqual(logger.getEffectiveLevel())) {
            return FilterReply.DENY;
        }
        SourceMaskedEvent event;
        try {
            // The class a LocationAwareLogger wrapper passed to Logback does not reach this method.
            event = new SourceMaskedEvent(null, logger, level, format, t, params);
            if (marker != null) {
                event.addMarker(marker);
            }
        } catch (RuntimeException | LinkageError e) {
            return fallBack(e);
        }
        logger.callAppenders(event);
        return FilterReply.DENY;
    }

    /** The fluent API has already checked the level; Logback only looks for {@code DENY} here, as does this filter. */
    @Override
    public FilterReply decide(Logger logger, LoggingEvent slf4jEvent) {
        if (!alone(logger) && decisionOfOthers(logger, filter -> filter.decide(logger, slf4jEvent)) == FilterReply.DENY) {
            return FilterReply.DENY;
        }
        SourceMaskedEvent event;
        try {
            var boundary = slf4jEvent.getCallerBoundary();
            event = new SourceMaskedEvent(boundary == null ? FQCN : boundary, logger,
                    Level.convertAnSLF4JLevel(slf4jEvent.getLevel()), slf4jEvent.getMessage(),
                    slf4jEvent.getThrowable(), slf4jEvent.getArgumentArray());
            var markers = slf4jEvent.getMarkers();
            if (markers != null) {
                markers.forEach(event::addMarker);
            }
            event.setKeyValuePairs(slf4jEvent.getKeyValuePairs());
        } catch (RuntimeException | LinkageError e) {
            return fallBack(e);
        }
        logger.callAppenders(event);
        return FilterReply.DENY;
    }

    /**
     * Usually this is the only filter and the level decides. The check precedes the lambda for other
     * filters: the call runs for every line, even a disabled one, and a capturing lambda would cost it an
     * allocation.
     */
    private static boolean alone(Logger logger) {
        return logger.getLoggerContext().getTurboFilterList().size() == 1;
    }

    /** The first non-{@code NEUTRAL} reply among the filters after this one, as in the Logback chain. */
    private FilterReply decisionOfOthers(Logger logger, Function<TurboFilter, FilterReply> decide) {
        var afterSelf = false;
        for (var filter : logger.getLoggerContext().getTurboFilterList()) {
            if (!afterSelf) {
                afterSelf = filter == this;
                continue;
            }
            var reply = decide.apply(filter);
            if (reply != FilterReply.NEUTRAL) {
                return reply;
            }
        }
        return FilterReply.NEUTRAL;
    }

    /**
     * The event could not be built, so Logback is incompatible with the filter; the masker is not broken
     * (its failure yields a placeholder inside the event). Logback builds the event instead: the line
     * stays covered by the encoder wrapper, but the unmasked source must be reported. The flag is set
     * before writing: the failure line comes back to the filter, which passes it to Logback without a
     * second attempt.
     * <p>The reply is {@code ACCEPT}: the other filters and the level have already decided, and on
     * {@code NEUTRAL} Logback would ask them again, so counters such as {@code DuplicateMessageFilter}
     * would count every line twice.
     */
    private FilterReply fallBack(Throwable failure) {
        if (failureReported.compareAndSet(false, true)) {
            try {
                LoggerFactory.getLogger(MaskingTurboFilter.class).warn("Log masking at the source failed: "
                        + "events reach appenders and Java agents unmasked, only encoder output stays masked; "
                        + "further failures are not logged", failure);
            } catch (RuntimeException | LinkageError e) {
                // Logging is broken entirely; there is nowhere to report.
            }
        }
        return FilterReply.ACCEPT;
    }

    /**
     * Adds the filter first to the context chain if it is absent, and moves it back to the front if
     * another filter got ahead of it.
     * @return {@code true} if the chain had to change
     */
    static boolean attach(LoggerContext context) {
        var filters = context.getTurboFilterList();
        MaskingTurboFilter attached = null;
        for (var filter : filters) {
            if (filter instanceof MaskingTurboFilter masking) {
                attached = masking;
                break;
            }
        }
        if (attached != null) {
            if (filters.get(0) == attached) {
                return false;
            }
            // A single array swap: between remove and add, lines from other threads would bypass the filter.
            var front = attached;
            filters.sort(Comparator.comparing(filter -> filter != front));
            return true;
        }
        var filter = new MaskingTurboFilter();
        filter.setContext(context);
        filter.start();
        filters.add(0, filter);
        return true;
    }
}

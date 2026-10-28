package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.TurboFilterList;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("MaskingWatchdog: wrappers after Logback reconfiguration")
class MaskingWatchdogTest {

    private static final String CONFIG = """
            <configuration>
              <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                <encoder><pattern>%msg%n</pattern></encoder>
              </appender>
              <root level="INFO"><appender-ref ref="CONSOLE"/></root>
            </configuration>
            """;

    private static final String NO_APPENDERS = """
            <configuration>
              <root level="INFO"/>
            </configuration>
            """;

    private final LoggerContext context = new LoggerContext();

    {
        context.setMDCAdapter(new LogbackMDCAdapter());
    }

    @AfterEach
    void stopContext() {
        context.stop();
    }

    @Test
    @DisplayName("XML reloaded (scan=\"true\"): the wrapper returns immediately, on the configuration thread")
    void onConfigurationEvent_whenXmlReconfigured_shouldWrapNewEncoderImmediately() throws Exception {
        configure();

        try (var ignored = MaskingWatchdog.start(Duration.ZERO, () -> context)) {
            configure();
            assertThat(consoleEncoder()).as("The subscription survives reset()").isInstanceOf(MaskingEncoder.class);
        }

        configure();
        assertThat(consoleEncoder()).as("No subscription after close").isNotInstanceOf(MaskingEncoder.class);
    }

    @Test
    @DisplayName("Logging suppressed, as Spring does on rebuild: the wrap count waits for the post-refresh pass")
    void onConfigurationEvent_whenLoggingSuppressed_shouldKeepWrappedCountForLaterReport() throws Exception {
        configure();
        var suppressAll = new TurboFilter() {
            @Override
            public FilterReply decide(org.slf4j.Marker marker, ch.qos.logback.classic.Logger logger, Level level,
                                      String format, Object[] params, Throwable t) {
                return FilterReply.DENY;
            }
        };

        MaskingWatchdog.takeUnreported();

        try (var ignored = MaskingWatchdog.start(Duration.ZERO, () -> context)) {
            configure();
            assertThat(MaskingWatchdog.takeUnreported()).as("Not suppressed: the line is already written").isZero();

            globalWatchdogLogger().getLoggerContext().addTurboFilter(suppressAll);
            try {
                configure();
            } finally {
                globalWatchdogLogger().getLoggerContext().getTurboFilterList().remove(suppressAll);
            }
            assertThat(MaskingWatchdog.takeUnreported()).isEqualTo(1);
            assertThat(MaskingWatchdog.takeUnreported()).as("Each wrapper is reported once").isZero();
        }
    }

    @Test
    @DisplayName("Source not re-attached after reconfiguration: INFO does not claim it, WARN reports it")
    void onConfigurationEvent_whenSourceFails_shouldNotClaimItAndWarn() throws Exception {
        var rejecting = rejectingTurboFilters();
        configure(rejecting, CONFIG);
        var log = captureWatchdogLog();

        try (var ignored = MaskingWatchdog.start(Duration.ZERO, () -> rejecting)) {
            configure(rejecting, CONFIG);
        } finally {
            globalWatchdogLogger().detachAppender(log);
            rejecting.stop();
        }

        assertThat(log.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.INFO, Level.WARN);
        assertThat(log.list.get(0).getFormattedMessage()).doesNotContain("event source");
        assertThat(log.list.get(1).getFormattedMessage()).contains("could not be re-attached to the event source");
    }

    @Test
    @DisplayName("Source not re-attached and nothing to wrap: the failure is still logged")
    void onConfigurationEvent_whenSourceFailsAndNothingWrapped_shouldStillWarn() throws Exception {
        var rejecting = rejectingTurboFilters();
        var log = captureWatchdogLog();

        try (var ignored = MaskingWatchdog.start(Duration.ZERO, () -> rejecting)) {
            configure(rejecting, NO_APPENDERS);
        } finally {
            globalWatchdogLogger().detachAppender(log);
            rejecting.stop();
        }

        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("could not be re-attached to the event source");
        });
    }

    @Test
    @DisplayName("An appender added from code fires no event, so the periodic pass picks it up")
    void check_whenAppenderAddedFromCode_shouldWrapItOnSchedule() {
        var appender = consoleAppender(new AtomicInteger());

        try (var ignored = MaskingWatchdog.start(Duration.ofMillis(20), () -> context)) {
            context.getLogger("ROOT").addAppender(appender);
            await().atMost(Duration.ofSeconds(5)).until(() -> appender.getEncoder() instanceof MaskingEncoder);
        }
    }

    @Test
    @DisplayName("One failed pass does not cancel the following ones")
    void check_whenOnePassFails_shouldKeepCheckingOnSchedule() {
        var appender = consoleAppender(new AtomicInteger(1));
        context.getLogger("ROOT").addAppender(appender);

        try (var ignored = MaskingWatchdog.start(Duration.ofMillis(20), () -> context)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> appender.getEncoder() instanceof MaskingEncoder);
        }
    }

    @Test
    @DisplayName("A pass that fails every time logs one WARN, not a line per pass")
    void check_whenEveryPassFails_shouldWarnOnce() {
        var failures = new AtomicInteger(Integer.MAX_VALUE);
        var appender = consoleAppender(failures);
        context.getLogger("ROOT").addAppender(appender);
        var warnings = captureWatchdogLog();

        try (var ignored = MaskingWatchdog.start(Duration.ofMillis(20), () -> context)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> Integer.MAX_VALUE - failures.get() >= 3);
        } finally {
            globalWatchdogLogger().detachAppender(warnings);
        }

        assertThat(warnings.list).singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    /** Like a scan reload: resets the context and parses the same file again. */
    private void configure() throws JoranException {
        configure(context, CONFIG);
    }

    private static void configure(LoggerContext target, String xml) throws JoranException {
        target.reset();
        var configurator = new JoranConfigurator();
        configurator.setContext(target);
        configurator.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** A context whose turbo filter chain cannot take the mask. */
    private static LoggerContext rejectingTurboFilters() {
        var rejecting = new LoggerContext() {
            @Override
            public TurboFilterList getTurboFilterList() {
                throw new IllegalStateException("chain unavailable");
            }
        };
        rejecting.setMDCAdapter(new LogbackMDCAdapter());
        return rejecting;
    }

    private Object consoleEncoder() {
        var appender = (OutputStreamAppender<ILoggingEvent>) context.getLogger("ROOT").getAppender("CONSOLE");
        return appender.getEncoder();
    }

    /** A console appender that fails on the watchdog thread until {@code failures} run out. */
    private ConsoleAppender<ILoggingEvent> consoleAppender(AtomicInteger failures) {
        var appender = new ConsoleAppender<ILoggingEvent>() {
            @Override
            public Encoder<ILoggingEvent> getEncoder() {
                if (Thread.currentThread().getName().equals("log-masking-watchdog")
                        && failures.getAndDecrement() > 0) {
                    throw new IllegalStateException("broken appender");
                }
                return super.getEncoder();
            }
        };
        appender.setContext(context);
        appender.setName("FROM_CODE");
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        appender.setEncoder(encoder);
        return appender;
    }

    private static ListAppender<ILoggingEvent> captureWatchdogLog() {
        var warnings = new ListAppender<ILoggingEvent>();
        warnings.setContext(globalWatchdogLogger().getLoggerContext());
        warnings.start();
        globalWatchdogLogger().addAppender(warnings);
        return warnings;
    }

    private static ch.qos.logback.classic.Logger globalWatchdogLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MaskingWatchdog.class);
    }
}

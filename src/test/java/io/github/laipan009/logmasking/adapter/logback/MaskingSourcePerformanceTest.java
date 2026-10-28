package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Measures the cost of masking across the whole logger call, from {@code log.info} to bytes in the stream.
 * Compares Logback without masking, the encoder wrapper alone, and the source together with the
 * wrapper. Prints a report without thresholds, since absolute timings depend on the machine. Uses the same
 * method as {@link MaskingEncoderPerformanceTest}: the best of several warmed-up runs.
 * <p>Not part of the regular build: {@code mvn test -Pperf}.
 */
@Tag("perf")
@DisplayName("Measurement: logger call")
class MaskingSourcePerformanceTest {

    private static final String PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n";

    private static final int WARMUP = 20_000;
    private static final int ITERATIONS = 50_000;
    private static final int ROUNDS = 5;

    private static final List<String> REPORT = new ArrayList<>();

    private LoggerContext context;
    private boolean consumeThrowable;

    @AfterEach
    void releaseMasker() {
        MaskingLogbackSupport.useLineMasker(null);
    }

    @AfterAll
    static void printReport() {
        System.out.println();
        System.out.println("  line                            unmasked   encoder only        source + encoder");
        System.out.println("  ------------------------------------------------------------------------------");
        REPORT.forEach(System.out::println);
        System.out.println();
    }

    @Test
    @DisplayName("Ordinary line without sensitive data")
    void log_whenLineHasNoPersonalData_shouldReportCost() {
        measure("Ordinary line", logger -> logger.info("Started SampleApplication in {} seconds", 2.431));
    }

    @Test
    @DisplayName("LoggingAspect argument dump with sensitive data")
    void log_whenAspectDumpContainsPersonalData_shouldReportCost() {
        var dump = "{request=CustomerDto(firstName=John, lastName=Smith, snils=12345678900, "
                + "email=ivan@example.com, position=engineer, attempt=3)}";
        measure("Sensitive aspect dump", logger -> logger.info(
                "[CustomerController]: Start execution method - update(), with params: {}", dump));
    }

    @Test
    @DisplayName("Embedded 4 KB body: the scanner's worst case, now scanned twice")
    void log_whenLineEmbedsFourKilobyteJsonBody_shouldReportCost() {
        var body = jsonBodyOfAtLeast(4096);
        measure("Embedded JSON, 4 KB", logger -> logger.info("← Body: {}", body));
    }

    @Test
    @DisplayName("Sensitive exception: the encoder renders the stack trace")
    void log_whenExceptionHoldsPersonalData_shouldReportCost() {
        var failure = new IllegalStateException("CustomerDto(snils=12345678900)",
                new IllegalArgumentException("timeout"));
        measure("Sensitive exception", logger -> logger.error("save failed", failure));
    }

    @Test
    @DisplayName("Sensitive exception taken by a Throwable consumer, like the OpenTelemetry agent")
    void log_whenThrowableConsumerTakesException_shouldReportCost() {
        var failure = new IllegalStateException("CustomerDto(snils=12345678900)",
                new IllegalArgumentException("timeout"));
        consumeThrowable = true;
        try {
            measure("Exception + OTel", logger -> logger.error("save failed", failure));
        } finally {
            consumeThrowable = false;
        }
    }

    @Test
    @DisplayName("Disabled level: the cost of a call that writes nothing")
    void log_whenLevelIsDisabled_shouldReportCost() {
        measure("debug at INFO level", logger -> logger.debug("snils={}", "12345678900"));
    }

    private void measure(String label, Consumer<Logger> call) {
        var plain = nanosPerCall(false, false, call);
        var encoder = nanosPerCall(false, true, call);
        var source = nanosPerCall(true, true, call);
        REPORT.add(String.format("  %-28s %8.0f ns %14.0f ns %17.0f ns", label, plain, encoder, source));
    }

    private double nanosPerCall(boolean atSource, boolean encoder, Consumer<Logger> call) {
        var logger = logger();
        var appender = (OutputStreamAppender<ILoggingEvent>) logger.getAppender("sink");
        if (encoder) {
            MaskingLogbackSupport.useLineMasker(new LineMasker(
                    new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()))));
            var masking = new MaskingEncoder(appender.getEncoder());
            masking.setContext(context);
            masking.start();
            appender.setEncoder(masking);
        }
        if (atSource) {
            MaskingTurboFilter.attach(context);
        }
        for (var i = 0; i < WARMUP; i++) {
            call.accept(logger);
        }
        var best = Long.MAX_VALUE;
        for (var round = 0; round < ROUNDS; round++) {
            var started = System.nanoTime();
            for (var i = 0; i < ITERATIONS; i++) {
                call.accept(logger);
            }
            best = Math.min(best, System.nanoTime() - started);
        }
        context.stop();
        return (double) best / ITERATIONS;
    }

    private Logger logger() {
        context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        var appender = new OutputStreamAppender<ILoggingEvent>();
        appender.setName("sink");
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(OutputStream.nullOutputStream());
        appender.start();
        var logger = context.getLogger("com.example.logging.LoggingAspect");
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
        if (consumeThrowable) {
            logger.addAppender(throwableConsumer());
        }
        return logger;
    }

    /** Does what the OpenTelemetry mapper does with an exception: takes the Throwable and prints its stack trace. */
    private AppenderBase<ILoggingEvent> throwableConsumer() {
        var consumer = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getThrowableProxy() instanceof ThrowableProxy proxy) {
                    var throwable = proxy.getThrowable();
                    var out = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(out));
                    if (out.getBuffer().isEmpty() || throwable.getClass().getName().isEmpty()) {
                        throw new AssertionError();
                    }
                }
            }
        };
        consumer.setContext(context);
        consumer.start();
        return consumer;
    }

    private static String jsonBodyOfAtLeast(int bytes) {
        var items = new StringBuilder();
        var size = 0;
        var index = 0;
        while (size < bytes) {
            var item = (items.isEmpty() ? "" : ",")
                    + "{\"customerId\":\"E-" + index + "\",\"fullName\":\"John Smith\","
                    + "\"position\":\"engineer\",\"email\":\"ivan" + index + "@example.com\"}";
            items.append(item);
            size += item.getBytes(StandardCharsets.UTF_8).length;
            index++;
        }
        return "{\"items\":[" + items + "]}";
    }
}

package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.encoder.Encoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures encoder rendering with and without masking, without machine-dependent thresholds. This is a
 * lightweight comparison, not JMH. Uses the best of several warmed-up runs to reduce GC and CPU-
 * frequency noise. Run separately with {@code mvn test -Pperf}.
 */
@Tag("perf")
@DisplayName("Measurement: encoder")
class MaskingEncoderPerformanceTest {

    /**
     * Example application pattern from logback-spring.xml.
     */
    private static final String PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n";

    private static final int WARMUP = 20_000;
    private static final int ITERATIONS = 50_000;
    private static final int ROUNDS = 5;

    private static final List<String> REPORT = new ArrayList<>();

    private static LoggerContext context;
    private static ch.qos.logback.classic.Logger logger;

    @BeforeAll
    static void setUp() {
        context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        logger = context.getLogger("com.example.logging.LoggingAspect");
        MaskingLogbackSupport.useLineMasker(new LineMasker(
                new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()))));
    }

    @AfterAll
    static void releaseMasker() {
        MaskingLogbackSupport.useLineMasker(null);
    }

    @AfterAll
    static void printReport() {
        System.out.println();
        System.out.println("  line                            unmasked          masked        overhead");
        System.out.println("  ---------------------------------------------------------------------------------");
        REPORT.forEach(System.out::println);
        System.out.println();
    }

    @Test
    @DisplayName("Ordinary non-sensitive line, representing typical traffic")
    void encode_whenLineHasNoPersonalData_shouldReportCost() {
        measure("Ordinary line", "Started SampleApplication in 2.431 seconds (process running for 2.8)");
    }

    @Test
    @DisplayName("Sensitive argument dumps require rebuilding the rendered line")
    void encode_whenAspectDumpContainsPersonalData_shouldReportCost() {
        measure("Aspect dump with sensitive data",
                "[CustomerController]: Start execution method - update(), with params: "
                        + "{request=CustomerDto(firstName=John, lastName=Smith, snils=12345678900, "
                        + "email=ivan@example.com, position=engineer, attempt=3)}");
    }

    @Test
    @DisplayName("Embedded 4 KB body exercises scanner overhead")
    void encode_whenLineEmbedsFourKilobyteJsonBody_shouldReportCost() {
        measure("Embedded JSON, 4 KB", jsonBodyOfAtLeast(4096));
    }

    @Test
    @DisplayName("Throughput: fraction of one core at 10k lines/sec")
    void encode_whenMaskingTenThousandLinesPerSecond_shouldReportCoreShare() {
        var event = event("[CustomerController]: update(), with params: "
                + "{request=CustomerDto(firstName=John, snils=12345678900)}");
        var plain = nanosPerCall(plainEncoder(), event);
        var masked = nanosPerCall(new MaskingEncoder(plainEncoder()), event);
        var addedCorePercentAt10k = (masked - plain) * 10_000 / 1e9 * 100;

        REPORT.add(String.format("  %-32s %13s    %15s %9.2f%% of one core",
                "10k lines/sec", "", "", addedCorePercentAt10k));
    }

    private static Encoder<ILoggingEvent> plainEncoder() {
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        return encoder;
    }

    private static ILoggingEvent event(String message) {
        var loggingEvent = new LoggingEvent(
                MaskingEncoderPerformanceTest.class.getName(), logger, Level.INFO, message, null, null);
        loggingEvent.setThreadName("http-nio-8080-exec-3");
        return loggingEvent;
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
        return "→ body: {\"items\":[" + items + "]}";
    }

    private static double nanosPerCall(Encoder<ILoggingEvent> encoder, ILoggingEvent event) {
        var sink = 0L;
        for (var i = 0; i < WARMUP; i++) {
            sink += encoder.encode(event).length;
        }
        var best = Long.MAX_VALUE;
        for (var round = 0; round < ROUNDS; round++) {
            var started = System.nanoTime();
            for (var i = 0; i < ITERATIONS; i++) {
                sink += encoder.encode(event).length;
            }
            best = Math.min(best, System.nanoTime() - started);
        }
        assertThat(sink).isPositive();
        return (double) best / ITERATIONS;
    }

    private void measure(String label, String message) {
        var event = event(message);
        var plain = nanosPerCall(plainEncoder(), event);
        var masked = nanosPerCall(new MaskingEncoder(plainEncoder()), event);
        REPORT.add(String.format("  %-32s %13.0f ns %15.0f ns %10.2fx",
                label, plain, masked, masked / plain));
    }
}

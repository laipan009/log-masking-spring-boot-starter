package io.github.laipan009.logmasking.core;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures allocation per line without thresholds. ThreadMXBean accounting varies across JVMs; a local
 * zero-byte result can differ from CI. Run separately with {@code mvn test -Pperf}.
 */
@Tag("perf")
@DisplayName("Measurement: allocation per line")
class LineMaskerAllocationTest {

    private static final int ITERATIONS = 100_000;

    private static final List<String> REPORT = new ArrayList<>();

    private static ThreadMXBean threads;

    private final LineMasker masker = new LineMasker(new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy())));

    @BeforeAll
    static void requireAllocationCounter() {
        Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported(), "Allocation counter unavailable on this JVM");
        threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
    }

    @AfterAll
    static void printReport() {
        System.out.println();
        System.out.println("  line                                 bytes per call");
        System.out.println("  ---------------------------------------------------");
        REPORT.forEach(System.out::println);
        System.out.println();
    }

    @Test
    @DisplayName("Ordinary log line without sensitive data")
    void mask_whenPlainLogLineHasNoMatch_shouldReportAllocation() {
        measure("Ordinary line", "2026-09-01 12:03:44.120 INFO [http-nio-8080-exec-3] r.s.h.Service - "
                + "Started SampleApplication in 2.431 seconds (process running for 3.1)");
    }

    @Test
    @DisplayName("JSON entry without sensitive data")
    void mask_whenJsonLineHasNoPii_shouldReportAllocation() {
        measure("JSON without sensitive data", "{\"@timestamp\":\"2026-09-01T12:03:44.120Z\",\"level\":\"INFO\","
                + "\"logger\":\"r.s.h.Service\",\"thread\":\"http-nio-8080-exec-3\","
                + "\"message\":\"handled request in 12ms\",\"traceId\":\"a1b2c3\"}");
    }

    @Test
    @DisplayName("Sensitive line: rebuilding required")
    void mask_whenLineContainsPii_shouldReportAllocation() {
        var line = "CustomerDto(snils=12345678900, position=engineer)";

        assertThat(masker.mask(line)).isNotEqualTo(line);
        measure("Sensitive line", line);
    }

    private void measure(String label, String line) {
        var sink = 0;
        for (var i = 0; i < ITERATIONS; i++) {
            sink += masker.mask(line).length();
        }
        var id = Thread.currentThread().threadId();
        var before = threads.getThreadAllocatedBytes(id);
        for (var i = 0; i < ITERATIONS; i++) {
            sink += masker.mask(line).length();
        }
        var allocated = threads.getThreadAllocatedBytes(id) - before;
        assertThat(sink).isPositive();
        REPORT.add(String.format("  %-36s %8d", label, allocated / ITERATIONS));
    }
}

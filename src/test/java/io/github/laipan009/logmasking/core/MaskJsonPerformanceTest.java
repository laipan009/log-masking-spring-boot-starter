package io.github.laipan009.logmasking.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures the {@code LogMasker.maskJson} path, whose cost grows with payload size. Prints
 * measurements without machine-dependent thresholds. Run separately with {@code mvn test -Pperf}.
 */
@Tag("perf")
@DisplayName("Measurement: maskJson")
class MaskJsonPerformanceTest {

    private static final int WARMUP_ITERATIONS = 200;
    private static final int MEASURED_ITERATIONS = 200;

    private static final List<String> REPORT = new ArrayList<>();

    private final MaskingPolicyRegistry registry = new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()));
    private final LogMasker masker = new LogMasker(new PayloadSanitizer(new ObjectMapper(), registry), registry);

    @AfterAll
    static void printReport() {
        System.out.println();
        System.out.println("  body                          ms per call");
        System.out.println("  -----------------------------------------");
        REPORT.forEach(System.out::println);
        System.out.println();
    }

    @Test
    void maskJson_whenBodyIsSmall_shouldReportTime() {
        measure("1 KB", payloadOfAtLeast(1024));
    }

    @Test
    void maskJson_whenBodyIsNearTheLimit_shouldReportTime() {
        var payload = payloadOfAtLeast(60 * 1024);

        assertThat(sizeInBytes(payload))
                .as("The body must remain within the limit to exercise parsing")
                .isLessThan(PayloadSanitizer.MAX_BODY_BYTES);
        measure("60 KB", payload);
    }

    /**
     * Measures UTF-8 bytes rather than characters; multibyte text can exceed the body limit before parsing
     * if measured by string length alone.
     */
    private static String payloadOfAtLeast(int bytes) {
        var entries = new StringBuilder();
        var size = 0;
        var index = 0;
        while (size < bytes) {
            var entry = (entries.isEmpty() ? "" : ",")
                    + "{\"customerId\":\"E-" + index
                    + "\",\"fullName\":\"John Smith\",\"position\":\"engineer\","
                    + "\"email\":\"ivan" + index + "@example.com\"}";
            entries.append(entry);
            size += entry.getBytes(StandardCharsets.UTF_8).length;
            index++;
        }
        return "{\"items\":[" + entries + "]}";
    }

    private static int sizeInBytes(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8).length;
    }

    private void measure(String label, String payload) {
        assertThat(masker.maskJson(payload)).doesNotContain("John Smith");
        IntStream.range(0, WARMUP_ITERATIONS).forEach(i -> masker.maskJson(payload));
        var started = System.nanoTime();
        IntStream.range(0, MEASURED_ITERATIONS).forEach(i -> masker.maskJson(payload));
        var millis = (System.nanoTime() - started) / 1_000_000.0 / MEASURED_ITERATIONS;
        REPORT.add(String.format("  %-28s %10.3f", label, millis));
    }
}

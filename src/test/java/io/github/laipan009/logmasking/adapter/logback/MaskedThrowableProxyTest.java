package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.logback.SentryAppender;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicy;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.io.PrintWriter;
import java.io.Serial;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Exceptions for consumers that take the {@code Throwable} itself from the event: the OpenTelemetry agent and
 * appender, Sentry, Spring Boot's {@code StackTracePrinter}. They print it themselves, bypassing the encoder, so
 * the mask must be in the object itself.
 */
@DisplayName("MaskedThrowableProxy: exceptions for OpenTelemetry and Sentry")
class MaskedThrowableProxyTest {

    private static final String SNILS = "12345678900";
    private static final String INN = "7701234567";
    private static final String FULL_NAME = "John Smith";

    private final LoggerContext context = new LoggerContext();
    private final ListAppender<ILoggingEvent> received = new ListAppender<>();
    private Logger logger;

    {
        context.setMDCAdapter(new LogbackMDCAdapter());
    }

    @BeforeEach
    void setUp() {
        MaskingLogbackSupport.useLineMasker(new LineMasker(new MaskingPolicyRegistry(
                List.of(new DefaultMaskingPolicy(), new MaskingPolicy() {
                    @Override
                    public Set<String> maskedFields() {
                        return Set.of("innId", "fullName");
                    }
                }))));
        received.setContext(context);
        received.start();
        logger = context.getLogger("com.example.test");
        logger.addAppender(received);
        MaskingAppenderInstaller.install(context);
    }

    @AfterEach
    void tearDown() {
        context.stop();
        MaskingLogbackSupport.useLineMasker(null);
    }

    @Nested
    @DisplayName("Exception copy")
    class Copy {

        @Test
        @DisplayName("Masks messages across the chain; the first-line class and the frames stay original")
        void whenChainHoldsPersonalData_shouldGiveMaskedCopy() {
            var cause = new IllegalArgumentException("innId=" + INN);
            var failure = new IllegalStateException("CustomerDto(snils=" + SNILS + ")", cause);
            failure.addSuppressed(new RuntimeException("fullName=" + FULL_NAME));

            var copy = throwableOf(failure);

            assertThat(copy).isNotSameAs(failure).isInstanceOf(MaskedThrowable.class);
            assertThat(copy.getMessage()).isEqualTo("CustomerDto(snils=***)");
            assertThat(copy.getStackTrace()).isEqualTo(failure.getStackTrace());
            assertThat(stackTraceOf(copy))
                    .startsWith("java.lang.IllegalStateException: CustomerDto(snils=***)")
                    .contains("Caused by: java.lang.IllegalArgumentException: innId=***")
                    .contains("Suppressed: java.lang.RuntimeException: fullName=***")
                    .contains("at " + MaskedThrowableProxyTest.Copy.class.getName())
                    .doesNotContain(SNILS, INN, FULL_NAME);
        }

        @Test
        @DisplayName("Nothing to mask: consumers get the original exception, so Sentry and OTel see the real type")
        void whenChainIsClean_shouldGiveOriginalThrowable() {
            var failure = new IllegalStateException("timeout", new java.io.IOException("connection reset"));

            assertThat(throwableOf(failure)).isSameAs(failure);
        }

        @Test
        @DisplayName("A clean cause of a masked exception stays original")
        void whenOnlyTopHoldsPersonalData_shouldKeepOriginalCause() {
            var cause = new java.io.IOException("connection reset");
            var failure = new IllegalStateException("snils=" + SNILS, cause);

            var copy = throwableOf(failure);

            assertThat(copy.getMessage()).isEqualTo("snils=***");
            assertThat(copy.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("Sensitive data only in the cause copies the top too, otherwise getCause() exposes it")
        void whenOnlyCauseHoldsPersonalData_shouldCopyTopToo() {
            var cause = new IllegalArgumentException("innId=" + INN);
            var failure = new IllegalStateException("save failed", cause);

            var copy = throwableOf(failure);

            assertThat(copy).isNotSameAs(failure);
            assertThat(copy.getMessage()).isEqualTo("save failed");
            assertThat(copy.getCause().getMessage()).isEqualTo("innId=***");
        }

        @Test
        @DisplayName("A custom toString() with sensitive data is printed masked even though getMessage() is clean")
        void whenToStringHoldsPersonalData_shouldMaskFirstLine() {
            var copy = throwableOf(new DumpingException(SNILS));

            assertThat(copy.toString()).isEqualTo("DumpingException(snils=***)");
            assertThat(stackTraceOf(copy)).doesNotContain(SNILS);
        }

        /**
         * Logback breaks a cycle with a leaf: the second occurrence of the top exception. The leaf is clean, and
         * without a special case it would stay original, while the original's {@code getCause()} leads back to
         * the cause with sensitive data.
         */
        @Test
        @DisplayName("Cyclic chain: everything is copied, the original exception is unreachable from the copy")
        void whenChainIsCyclic_shouldCopyEverything() {
            var failure = new IllegalStateException("save failed");
            var cause = new IllegalArgumentException("snils=" + SNILS, failure);
            failure.initCause(cause);

            var copy = throwableOf(failure);

            var seen = new StringBuilder();
            var current = copy;
            for (var depth = 0; depth < 5 && current != null; depth++) {
                assertThat(current).isNotSameAs(failure).isNotSameAs(cause);
                seen.append(current).append('\n');
                current = current.getCause();
            }
            assertThat(seen.toString()).contains("snils=***").doesNotContain(SNILS);
            assertThat(stackTraceOf(copy)).doesNotContain(SNILS);
        }

        @Test
        @DisplayName("Masker failure: the copy holds a placeholder, not the raw message")
        void whenMaskerFails_shouldSuppressMessageInCopy() {
            var broken = mock(LineMasker.class);
            given(broken.mask(anyString(), any())).willThrow(new IllegalStateException("boom"));
            MaskingLogbackSupport.useLineMasker(broken);

            var copy = throwableOf(new IllegalStateException("snils=" + SNILS));

            assertThat(copy.getMessage()).isEqualTo(MaskingLogbackSupport.SUPPRESSED);
        }

        private Throwable throwableOf(Throwable failure) {
            logger.error("save failed", failure);
            var event = received.list.get(received.list.size() - 1);
            assertThat(event.getThrowableProxy())
                    .as("Sentry casts the event's exception to ThrowableProxy without checking")
                    .isInstanceOf(ThrowableProxy.class);
            return ((ThrowableProxy) event.getThrowableProxy()).getThrowable();
        }
    }

    /**
     * The OpenTelemetry appender and agent build the record with the same {@code LoggingEventMapper}: the agent on
     * entry to {@code callAppenders}, the appender in {@code append}, both from the same event.
     */
    @Nested
    @DisplayName("OpenTelemetry: the same mapper as the agent")
    class OpenTelemetry {

        private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();

        @BeforeEach
        void attachOpenTelemetry() {
            var appender = new OpenTelemetryAppender();
            appender.setContext(context);
            appender.setOpenTelemetry(OpenTelemetrySdk.builder()
                    .setLoggerProvider(SdkLoggerProvider.builder()
                            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build())
                    .build());
            appender.setCaptureMdcAttributes("*");
            appender.setCaptureKeyValuePairAttributes(true);
            appender.setCaptureArguments(true);
            appender.start();
            logger.addAppender(appender);
        }

        @Test
        @DisplayName("Body, MDC, key-value pairs and the exception are exported to OTLP masked")
        void whenEventHoldsPersonalData_shouldExportItMasked() {
            context.getMDCAdapter().put("snils", SNILS);
            try {
                logger.atError().addKeyValue("innId", INN)
                        .setCause(new IllegalStateException("CustomerDto(fullName=" + FULL_NAME + ")"))
                        .log("Customer {}", "CustomerDto(snils=" + SNILS + ")");
            } finally {
                context.getMDCAdapter().clear();
            }

            var record = single();
            assertThat(record.getBodyValue().asString()).isEqualTo("Customer CustomerDto(snils=***)");
            assertThat(record.getAttributes().asMap().values()).extracting(String::valueOf)
                    .noneMatch(value -> value.contains(SNILS) || value.contains(INN) || value.contains(FULL_NAME));
            assertThat(attribute(record, "exception.message")).isEqualTo("CustomerDto(fullName=***)");
            assertThat(attribute(record, "exception.stacktrace"))
                    .startsWith("java.lang.IllegalStateException: CustomerDto(fullName=***)");
            assertThat(attribute(record, "exception.type"))
                    .as("The copy's class; the real one is in the first stack trace line")
                    .isEqualTo(MaskedThrowable.class.getName());
        }

        @Test
        @DisplayName("An exception without sensitive data is exported as is, with the real exception.type")
        void whenExceptionIsClean_shouldExportRealType() {
            logger.error("save failed", new IllegalStateException("timeout"));

            assertThat(attribute(single(), "exception.type")).isEqualTo(IllegalStateException.class.getName());
        }

        private LogRecordData single() {
            assertThat(exporter.getFinishedLogRecordItems()).hasSize(1);
            return exporter.getFinishedLogRecordItems().get(0);
        }

        private static String attribute(LogRecordData record, String key) {
            return record.getAttributes().get(AttributeKey.stringKey(key));
        }
    }

    @Nested
    @DisplayName("Sentry")
    class SentryConsumer {

        private final List<SentryEvent> sent = new CopyOnWriteArrayList<>();

        @BeforeEach
        void attachSentry() {
            var options = new SentryOptions();
            options.setDsn("https://public@localhost/1");
            options.setEnableUncaughtExceptionHandler(false);
            options.setEnableShutdownHook(false);
            options.setBeforeSend((event, hint) -> {
                sent.add(event);
                return null;
            });
            var appender = new SentryAppender();
            appender.setContext(context);
            appender.setOptions(options);
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void closeSentry() {
            Sentry.close();
        }

        @Test
        @DisplayName("An event with an exception is delivered with the message and exception masked")
        void whenErrorHoldsPersonalData_shouldSendItMasked() {
            context.getMDCAdapter().put("snils", SNILS);
            try {
                logger.error("Customer {}", "CustomerDto(snils=" + SNILS + ")",
                        new IllegalStateException("innId=" + INN));
            } finally {
                context.getMDCAdapter().clear();
            }

            assertThat(sent).as("Without ThrowableProxy, Sentry failed on the cast and lost the event").hasSize(1);
            var event = sent.get(0);
            assertThat(event.getMessage().getFormatted()).isEqualTo("Customer CustomerDto(snils=***)");
            assertThat(event.getExceptions()).extracting(SentryException::getValue).containsExactly("innId=***");
            assertThat(event.getThrowable()).hasMessage("innId=***");
            assertThat(String.valueOf(event.getContexts().get("MDC"))).contains("snils=***").doesNotContain(SNILS);
        }
    }

    private static String stackTraceOf(Throwable throwable) {
        var out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /** Exception with data in {@code toString()}: Logback prints it instead of {@code getMessage()}. */
    static final class DumpingException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String snils;

        DumpingException(String snils) {
            super("save failed");
            this.snils = snils;
        }

        @Override
        public String toString() {
            return "DumpingException(snils=" + snils + ")";
        }
    }
}

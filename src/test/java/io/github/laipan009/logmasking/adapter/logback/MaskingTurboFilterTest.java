package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.spi.CallerBoundaryAware;
import org.slf4j.spi.LocationAwareLogger;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller.Source;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.LogMasker;
import io.github.laipan009.logmasking.core.Mask;
import io.github.laipan009.logmasking.core.MaskScope;
import io.github.laipan009.logmasking.core.MaskStrategy;
import io.github.laipan009.logmasking.core.MaskingPolicy;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;
import io.github.laipan009.logmasking.core.PayloadSanitizer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Masking at the source: what every reader of the event receives, including appenders without an encoder,
 * Sentry and Java agents on {@code callAppenders}.
 * <p>The OpenTelemetry agent captures the event on entry to {@code Logger.callAppenders}, so it sees exactly
 * the object the appenders receive. An appender without an encoder therefore stands in for the agent here:
 * an unmasked value that reaches it would reach the agent too.
 */
@DisplayName("MaskingTurboFilter: events are masked before any consumer")
class MaskingTurboFilterTest {

    private static final String SNILS = "12345678900";
    private static final String INN = "7701234567";
    private static final String FULL_NAME = "John Smith";

    private final LoggerContext context = new LoggerContext();
    private final ListAppender<ILoggingEvent> received = new ListAppender<>();
    private Logger logger;

    {
        // a manually built context bypasses LogbackServiceProvider: without an MDC adapter
        // every append fails with an NPE before reaching the encoder
        context.setMDCAdapter(new LogbackMDCAdapter());
    }

    @BeforeEach
    void setUp() {
        MaskingLogbackSupport.useLineMasker(new LineMasker(registry(Map.of())));
        received.setContext(context);
        received.start();
        logger = context.getLogger("com.example.test");
        logger.setLevel(Level.INFO);
        logger.addAppender(received);
        MaskingAppenderInstaller.install(context);
    }

    @AfterEach
    void tearDown() {
        context.stop();
        MaskingLogbackSupport.useLineMasker(null);
    }

    @Nested
    @DisplayName("What consumers receive")
    class WhatConsumersGet {

        @Test
        @DisplayName("An appender without an encoder receives an already masked message")
        void whenAppenderHasNoEncoder_shouldReceiveMaskedMessage() {
            logger.info("Customer {}", "CustomerDto(snils=" + SNILS + ", innId=" + INN + ")");

            var event = single();
            assertThat(event.getFormattedMessage()).isEqualTo("Customer CustomerDto(snils=***, innId=***)");
            assertThat(event.getMessage())
                    .as("Template and arguments are not exposed: text built from them would be unmasked")
                    .isEqualTo(event.getFormattedMessage());
            assertThat(event.getArgumentArray()).isNull();
        }

        @Test
        @DisplayName("No original event is created: only the masked one reaches callAppenders and the agent")
        void whenLoggedInAnyForm_shouldDispatchOnlyMaskedEvents() {
            Marker marker = MarkerFactory.getMarker("AUDIT");
            logger.info("snils=" + SNILS);
            logger.info("snils={}", SNILS);
            logger.info("snils={} innId={}", SNILS, INN);
            logger.info("snils={} innId={} fullName={}", SNILS, INN, FULL_NAME);
            logger.info(marker, "snils={}", SNILS);
            logger.warn("snils={}", SNILS, new IllegalStateException("innId=" + INN));
            logger.log(marker, "org.example.Bridge", LocationAwareLogger.INFO_INT, "snils={}",
                    new Object[] {SNILS}, null);
            logger.atInfo().addKeyValue("snils", SNILS).log("fullName={}", FULL_NAME);

            assertThat(received.list).hasSize(8).allSatisfy(event -> {
                assertThat(event).isInstanceOf(SourceMaskedEvent.class);
                assertThat(event.getFormattedMessage()).doesNotContain(SNILS, INN, FULL_NAME);
            });
            assertThat(received.list.get(4).getMarkerList()).containsExactly(marker);
        }

        @Test
        @DisplayName("MDC is masked by key name and by content; technical keys stay untouched")
        void whenMdcHoldsPersonalData_shouldMaskIt() {
            context.getMDCAdapter().put("snils", SNILS);
            context.getMDCAdapter().put("request", "CustomerDto(innId=" + INN + ")");
            context.getMDCAdapter().put("traceId", "4bf92f3577b34da6");
            // the event reads MDC lazily, as Logback does: the appender reads it before the logger call returns
            try {
                logger.info("accepted");

                assertThat(single().getMDCPropertyMap())
                        .containsEntry("snils", "***")
                        .containsEntry("request", "CustomerDto(innId=***)")
                        .containsEntry("traceId", "4bf92f3577b34da6");
                assertThat(single().getMdc()).isEqualTo(single().getMDCPropertyMap());
            } finally {
                context.getMDCAdapter().clear();
            }
        }

        @Test
        @DisplayName("Fluent API key-value pairs are masked by name; untouched values keep their type")
        void whenFluentKeyValuesHoldPersonalData_shouldMaskThem() {
            logger.atInfo().addKeyValue("snils", SNILS).addKeyValue("attempt", 3).log("saved");

            var pairs = single().getKeyValuePairs();
            assertThat(pairs).extracting(pair -> pair.key).containsExactly("snils", "attempt");
            assertThat(pairs).extracting(pair -> pair.value).containsExactly("***", 3);
        }

        @Test
        @DisplayName("Exception messages are masked along the whole chain; the original Throwable is not exposed")
        void whenExceptionMessageHoldsPersonalData_shouldMaskWholeChain() {
            var cause = new IllegalArgumentException("innId=" + INN);
            var failure = new IllegalStateException("CustomerDto(snils=" + SNILS + ")", cause);
            failure.addSuppressed(new RuntimeException("fullName=" + FULL_NAME));

            logger.error("save failed {}", "T-1", failure);

            var proxy = single().getThrowableProxy();
            assertThat(proxy).isInstanceOfSatisfying(ThrowableProxy.class, withThrowable -> assertThat(
                    withThrowable.getThrowable()).as("The original getMessage() is unmasked").isNotSameAs(failure));
            assertThat(proxy.getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(proxy.getMessage()).isEqualTo("CustomerDto(snils=***)");
            assertThat(proxy.getCause().getMessage()).isEqualTo("innId=***");
            assertThat(proxy.getSuppressed()[0].getMessage()).isEqualTo("fullName=***");
            assertThat(proxy.getStackTraceElementProxyArray()).isNotEmpty();
            assertThat(single().getFormattedMessage()).isEqualTo("save failed T-1");
        }

        @Test
        @DisplayName("AsyncAppender: the event is already masked when it moves to another thread")
        void whenAppenderIsAsync_shouldPassMaskedEventToNestedAppender() {
            var nested = new ListAppender<ILoggingEvent>();
            nested.setContext(context);
            nested.start();
            var async = new AsyncAppender();
            async.setContext(context);
            async.addAppender(nested);
            async.start();
            logger.addAppender(async);

            logger.info("snils={}", SNILS);
            async.stop();

            assertThat(nested.list).singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo("snils=***"));
        }
    }

    @Nested
    @DisplayName("With the encoder wrapper")
    class WithEncoder {

        @Test
        @DisplayName("JSON inside JSON: the body is masked before the JSON encoder escapes quotes")
        void whenJsonBodyIsLoggedThroughJsonEncoder_shouldMaskItBeforeEscaping() throws Exception {
            var sink = streamTo(new LogstashEncoder());

            logger.info("Body: {}", "{\"snils\":\"" + SNILS + "\",\"innId\":\"" + INN + "\"}");

            var json = sink.toString(StandardCharsets.UTF_8);
            assertThat(json).doesNotContain(SNILS, INN);
            assertThat(new ObjectMapper().readTree(json).get("message").asText())
                    .isEqualTo("Body: {\"snils\":\"***\",\"innId\":\"***\"}");
        }

        @Test
        @DisplayName("MDC via %X{…}: the line has no key name, but the value is already masked in the event")
        void whenPatternPrintsMdcValueWithoutName_shouldPrintMask() {
            var sink = streamTo(patternEncoder("[%X{snils}] %msg%n"));
            context.getMDCAdapter().put("snils", SNILS);
            try {
                logger.info("request processed");
            } finally {
                context.getMDCAdapter().clear();
            }

            assertThat(sink.toString(StandardCharsets.UTF_8).stripTrailing()).isEqualTo("[***] request processed");
        }

        @Test
        @DisplayName("The JSON log stack trace is rendered from the masked exception")
        void whenExceptionIsLoggedThroughJsonEncoder_shouldRenderMaskedStackTrace() throws Exception {
            var sink = streamTo(new LogstashEncoder());

            logger.error("save failed", new IllegalStateException("CustomerDto(snils=" + SNILS + ")"));

            var stackTrace = new ObjectMapper().readTree(sink.toString(StandardCharsets.UTF_8))
                    .get("stack_trace").asText();
            assertThat(stackTrace).startsWith("java.lang.IllegalStateException: CustomerDto(snils=***)")
                    .doesNotContain(SNILS);
        }

        @Test
        @DisplayName("The encoder leaves already masked text alone: the @Mask strategy from field() is kept")
        void whenMaskedFieldIsLogged_shouldKeepItsOwnStrategy() throws Exception {
            var registry = registry(Map.of("snils", MaskStrategy.LAST4));
            MaskingLogbackSupport.useLineMasker(new LineMasker(registry));
            var logMasker = new LogMasker(new PayloadSanitizer(new ObjectMapper(), registry), registry);
            var sink = streamTo(patternEncoder("%msg%n"));
            var contact = new Contact("+79001234567", SNILS);

            // text after the placeholder: without arguments the encoder would treat it as part of the value
            logger.info("contact {} {} accepted", logMasker.field(contact, Contact::phone),
                    logMasker.field(contact, Contact::snils));

            assertThat(sink.toString(StandardCharsets.UTF_8).stripTrailing())
                    .isEqualTo("contact phone=***4567 snils=#" + sha256Prefix(SNILS) + " accepted");
        }

        record Contact(
                @Mask(scope = MaskScope.TYPED, strategy = MaskStrategy.LAST4) String phone,
                @Mask(scope = MaskScope.TYPED, strategy = MaskStrategy.HASH) String snils) {
        }
    }

    @Nested
    @DisplayName("Whether to log is decided as in Logback")
    class Decision {

        @Test
        @DisplayName("A level below the configured one produces no event")
        void whenLevelIsDisabled_shouldNotDispatch() {
            logger.debug("snils={}", SNILS);

            assertThat(received.list).isEmpty();
        }

        @Test
        @DisplayName("Another turbo filter denies: no event")
        void whenOtherFilterDenies_shouldNotDispatch() {
            context.addTurboFilter(replying(FilterReply.DENY, new AtomicInteger()));

            logger.info("snils={}", SNILS);

            assertThat(received.list).isEmpty();
        }

        @Test
        @DisplayName("Another turbo filter accepts: the event is dispatched below the level and is masked")
        void whenOtherFilterAccepts_shouldDispatchMaskedEventBelowLevel() {
            context.addTurboFilter(replying(FilterReply.ACCEPT, new AtomicInteger()));

            logger.debug("snils={}", SNILS);

            assertThat(single().getFormattedMessage()).isEqualTo("snils=***");
        }

        @Test
        @DisplayName("Other filters are asked once per call, so counters such as DuplicateMessageFilter stay accurate")
        void whenOtherFilterIsStateful_shouldAskItOncePerCall() {
            var calls = new AtomicInteger();
            context.addTurboFilter(replying(FilterReply.NEUTRAL, calls));

            logger.info("first");
            logger.info("second {}", 2);

            assertThat(calls).hasValue(2);
            assertThat(received.list).hasSize(2);
        }

        @Test
        @DisplayName("If the event cannot be built, Logback builds the line and other filters are not asked again")
        void whenSourceEventCannotBeBuilt_shouldAskOtherFiltersOnce() {
            var calls = new AtomicInteger();
            context.addTurboFilter(replying(FilterReply.NEUTRAL, calls));

            logger.info("save failed", new FailingOnceException());

            assertThat(calls).hasValue(1);
            assertThat(single()).isNotInstanceOf(SourceMaskedEvent.class);
        }

        @Test
        @DisplayName("isDebugEnabled() is unchanged: the filter ignores checks without a message")
        void whenLevelIsChecked_shouldAnswerAsLogback() {
            assertThat(logger.isInfoEnabled()).isTrue();
            assertThat(logger.isDebugEnabled()).isFalse();

            context.addTurboFilter(replying(FilterReply.ACCEPT, new AtomicInteger()));

            assertThat(logger.isDebugEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Caller data")
    class CallerData {

        @Test
        @DisplayName("Caller data points to application code, not to the filter the event passed through")
        void whenCallerDataIsRequested_shouldPointToApplicationCode() {
            var capture = callerCapture();

            logger.info("accepted");

            assertThat(capture.get(0).getClassName()).isEqualTo(CallerData.class.getName());
            assertThat(capture.get(0).getMethodName()).isEqualTo("whenCallerDataIsRequested_shouldPointToApplicationCode");
        }

        @Test
        @DisplayName("Fluent API: caller data also points to application code")
        void whenLoggedThroughFluentApi_shouldPointToApplicationCode() {
            var capture = callerCapture();

            logger.atInfo().log("accepted");

            assertThat(capture.get(0).getMethodName()).isEqualTo("whenLoggedThroughFluentApi_shouldPointToApplicationCode");
        }

        @Test
        @DisplayName("A LocationAwareLogger wrapper passes its own class: caller data points past it, as in Logback")
        void whenWrapperPassesItsClassToLocationAwareLogger_shouldPointPastWrapper() {
            var capture = callerCapture();

            LocationAwareWrapper.info(logger, "accepted");

            assertThat(capture.get(0).getMethodName())
                    .isEqualTo("whenWrapperPassesItsClassToLocationAwareLogger_shouldPointPastWrapper");
        }

        @Test
        @DisplayName("Fluent API with a caller boundary: caller data points past the wrapper that set it")
        void whenFluentWrapperSetsCallerBoundary_shouldPointPastWrapper() {
            var capture = callerCapture();

            FluentWrapper.info(logger, "accepted");

            assertThat(capture.get(0).getMethodName()).isEqualTo("whenFluentWrapperSetsCallerBoundary_shouldPointPastWrapper");
        }

        @Test
        @DisplayName("JCL and Log4j bridges and frameworkPackages wrappers are skipped")
        void whenCalledThroughBridge_shouldSkipBridgeFrames() {
            var stack = new StackTraceElement[] {
                    frame("com.example.CallerCapture", "append"),
                    frame("ch.qos.logback.classic.Logger", "callAppenders"),
                    frame(MaskingTurboFilter.class.getName(), "decide"),
                    frame("ch.qos.logback.classic.spi.TurboFilterList", "getTurboFilterChainDecision"),
                    frame("ch.qos.logback.classic.Logger", "log"),
                    frame("org.apache.commons.logging.LogAdapter$Slf4jLocationAwareLog", "info"),
                    frame("org.apache.logging.slf4j.SLF4JLogger", "logMessage"),
                    frame("com.example.logging.AuditLog", "write"),
                    frame("com.example.CustomerService", "update"),
                    frame("com.example.CustomerController", "post"),
            };

            var callerData = SourceMaskedEvent.extractCallerData(stack, null, 1, List.of("com.example.logging."));

            assertThat(callerData).containsExactly(frame("com.example.CustomerService", "update"));
        }

        @Test
        @DisplayName("A call entering through Logger.log uses the calling class, with all its frames, as the boundary")
        void whenEnteredThroughLocationAwareLog_shouldSkipCallingClassFrames() {
            var stack = new StackTraceElement[] {
                    frame("ch.qos.logback.classic.Logger", "callAppenders"),
                    frame(MaskingTurboFilter.class.getName(), "decide"),
                    frame("ch.qos.logback.classic.Logger", "log"),
                    frame("com.example.LogUtil", "write"),
                    frame("com.example.LogUtil", "info"),
                    frame("com.example.CustomerService", "update"),
            };

            assertThat(SourceMaskedEvent.extractCallerData(stack, null, 1, List.of()))
                    .containsExactly(frame("com.example.CustomerService", "update"));
        }

        @Test
        @DisplayName("An event inspected outside a logger call has no caller data, as in Logback")
        void whenStackHasNoDispatch_shouldReturnEmpty() {
            var stack = new StackTraceElement[] {frame("com.example.Worker", "run")};

            assertThat(SourceMaskedEvent.extractCallerData(stack, null, 8, List.of())).isEmpty();
        }

        private List<StackTraceElement> callerCapture() {
            var capture = new ArrayList<StackTraceElement>();
            var appender = new AppenderBase<ILoggingEvent>() {
                @Override
                protected void append(ILoggingEvent event) {
                    capture.add(event.getCallerData()[0]);
                }
            };
            appender.setContext(context);
            appender.start();
            logger.addAppender(appender);
            return capture;
        }

        private static StackTraceElement frame(String className, String method) {
            return new StackTraceElement(className, method, null, -1);
        }
    }

    /** Like a custom {@code LogUtil} or kotlin-logging: Logback learns the wrapper class from an argument. */
    static final class LocationAwareWrapper {

        static void info(LocationAwareLogger logger, String message) {
            logger.log(null, LocationAwareWrapper.class.getName(), LocationAwareLogger.INFO_INT, message, null, null);
        }
    }

    static final class FluentWrapper {

        static void info(Logger logger, String message) {
            var builder = logger.atInfo();
            ((CallerBoundaryAware) builder).setCallerBoundary(FluentWrapper.class.getName());
            builder.log(message);
        }
    }

    /** Fails on the first message read, so the filter cannot build its event while Logback still can. */
    static final class FailingOnceException extends RuntimeException {

        private final AtomicBoolean failed = new AtomicBoolean();

        @Override
        public String getMessage() {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("message unavailable");
            }
            return "save failed";
        }
    }

    @Nested
    @DisplayName("Installation")
    class Installation {

        @Test
        @DisplayName("The filter comes first and a repeated pass does not add a second one")
        void install_whenCalledAgain_shouldKeepSingleFilterFirst() {
            var second = MaskingAppenderInstaller.install(context);

            assertThat(second.source()).isEqualTo(Source.PRESENT);
            assertThat(context.getTurboFilterList()).first().isInstanceOf(MaskingTurboFilter.class);
            assertThat(context.getTurboFilterList()).filteredOn(MaskingTurboFilter.class::isInstance).hasSize(1);
        }

        @Test
        @DisplayName("Another filter inserted ahead of masking: the pass moves masking back to the front")
        void install_whenOtherFilterInsertedFirst_shouldMoveMaskingBackToFront() {
            context.getTurboFilterList().add(0, replying(FilterReply.ACCEPT, new AtomicInteger()));

            var report = MaskingAppenderInstaller.install(context);

            assertThat(report.source()).isEqualTo(Source.ATTACHED);
            assertThat(report.changed()).isTrue();
            assertThat(context.getTurboFilterList()).first().isInstanceOf(MaskingTurboFilter.class);
        }

        @Test
        @DisplayName("Moving to the front is a single chain swap: other threads never see the chain without masking")
        void install_whenMovingFilterToFront_shouldNeverLeaveChainWithoutIt() throws Exception {
            var filters = context.getTurboFilterList();
            var other = replying(FilterReply.NEUTRAL, new AtomicInteger());
            var running = new AtomicBoolean(true);
            var started = new CountDownLatch(1);
            var withoutMasking = new AtomicInteger();
            var observer = new Thread(() -> {
                started.countDown();
                while (running.get()) {
                    var found = false;
                    for (var filter : filters.toArray()) {
                        found |= filter instanceof MaskingTurboFilter;
                    }
                    if (!found) {
                        withoutMasking.incrementAndGet();
                    }
                }
            });
            observer.start();
            started.await();
            try {
                for (var i = 0; i < 200_000; i++) {
                    filters.add(0, other);
                    MaskingTurboFilter.attach(context);
                    filters.remove(other);
                }
            } finally {
                running.set(false);
                observer.join();
            }

            assertThat(withoutMasking).hasValue(0);
            assertThat(filters).first().isInstanceOf(MaskingTurboFilter.class);
        }

        @Test
        @DisplayName("Context reset() removes the filter; the next pass attaches it again")
        void install_whenContextWasReset_shouldAttachFilterAgain() {
            context.reset();
            var afterReset = new ListAppender<ILoggingEvent>();
            afterReset.setContext(context);
            afterReset.start();
            context.getLogger("com.example.test").addAppender(afterReset);

            var report = MaskingAppenderInstaller.install(context);
            context.getLogger("com.example.test").info("snils={}", SNILS);

            assertThat(report.source()).isEqualTo(Source.ATTACHED);
            assertThat(afterReset.list).singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo("snils=***"));
        }
    }

    @Test
    @DisplayName("Masker failure: a placeholder is written instead of unmasked text")
    void whenMaskerFails_shouldSuppressMessage() {
        var broken = mock(LineMasker.class);
        given(broken.mask(anyString(), any())).willThrow(new IllegalStateException("boom"));
        MaskingLogbackSupport.useLineMasker(broken);

        logger.info("snils={}", SNILS);

        assertThat(received.list).first()
                .satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo(MaskingLogbackSupport.SUPPRESSED));
    }

    private ILoggingEvent single() {
        assertThat(received.list).hasSize(1);
        return received.list.get(0);
    }

    private ByteArrayOutputStream streamTo(Encoder<ILoggingEvent> encoder) {
        var sink = new ByteArrayOutputStream();
        encoder.setContext(context);
        encoder.start();
        var appender = new OutputStreamAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(sink);
        appender.start();
        logger.addAppender(appender);
        MaskingAppenderInstaller.install(context);
        assertThat(appender.getEncoder()).isInstanceOf(MaskingEncoder.class);
        return sink;
    }

    private PatternLayoutEncoder patternEncoder(String pattern) {
        var encoder = new PatternLayoutEncoder();
        encoder.setPattern(pattern);
        encoder.setCharset(StandardCharsets.UTF_8);
        return encoder;
    }

    private static MaskingPolicyRegistry registry(Map<String, MaskStrategy> strategies) {
        return new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy(), new MaskingPolicy() {
            @Override
            public Set<String> maskedFields() {
                return Set.of("innId", "fullName");
            }

            @Override
            public Map<String, MaskStrategy> fieldStrategies() {
                return strategies;
            }
        }));
    }

    private static TurboFilter replying(FilterReply reply, AtomicInteger calls) {
        var filter = new TurboFilter() {
            @Override
            public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params,
                                      Throwable t) {
                if (format != null) {
                    calls.incrementAndGet();
                }
                return reply;
            }
        };
        filter.start();
        return filter;
    }

    private static String sha256Prefix(String value) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 8);
    }
}

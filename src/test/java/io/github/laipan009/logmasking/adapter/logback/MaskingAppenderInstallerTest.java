package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.appender.LogstashTcpSocketAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaskingAppenderInstaller: appender traversal")
class MaskingAppenderInstallerTest {

    private static final String PATTERN = "%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n";

    private final LoggerContext context = new LoggerContext();

    {
        // A manually constructed context bypasses LogbackServiceProvider; install an MDC adapter to avoid an
        // NPE before encoding.
        context.setMDCAdapter(new LogbackMDCAdapter());
    }

    @AfterEach
    void stopContext() {
        context.stop();
        MaskingLogbackSupport.useLineMasker(null);
    }

    @Test
    @DisplayName("Wraps the console encoder and masks actual output")
    void install_whenAppenderHasEncoder_shouldWrapEncoderAndMaskSensitiveOutput() {
        MaskingLogbackSupport.useLineMasker(new LineMasker(new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()))));
        var sink = new ByteArrayOutputStream();
        var logger = context.getLogger("com.example.test");
        logger.addAppender(streamAppender(sink));
        logger.setLevel(Level.INFO);

        var report = MaskingAppenderInstaller.install(context);
        logger.info("params: CustomerDto(firstName=John, snils=12345678900)");

        assertThat(report.wrapped()).isEqualTo(1);
        assertThat(report.isFullyCovered()).isTrue();
        assertThat(sink.toString(StandardCharsets.UTF_8))
                .doesNotContain("John")
                .doesNotContain("12345678900")
                .contains("firstName=***");
    }

    @Test
    @DisplayName("Repeated installation does not create nested wrappers")
    void install_whenCalledTwiceOnSameContext_shouldNotWrapEncoderAgain() {
        var logger = context.getLogger("com.example.test");
        logger.addAppender(streamAppender(new ByteArrayOutputStream()));

        MaskingAppenderInstaller.install(context);
        var second = MaskingAppenderInstaller.install(context);

        assertThat(second.wrapped()).isZero();
        assertThat(second.alreadyWrapped()).isEqualTo(1);
    }

    @Test
    @DisplayName("Wraps an appender shared by several loggers only once")
    void install_whenAppenderSharedBySeveralLoggers_shouldWrapItOnlyOnce() {
        var shared = streamAppender(new ByteArrayOutputStream());
        context.getLogger("com.example.a").addAppender(shared);
        context.getLogger("com.example.b").addAppender(shared);

        assertThat(MaskingAppenderInstaller.install(context).wrapped()).isEqualTo(1);
    }

    @Test
    @DisplayName("Traverses AsyncAppender to reach its nested appender")
    void install_whenAppenderIsAsyncWrapper_shouldWrapEncoderOfNestedAppender() {
        var console = new ConsoleAppender<ILoggingEvent>();
        console.setContext(context);
        console.setEncoder(encoder());
        console.start();
        var async = new AsyncAppender();
        async.setContext(context);
        async.addAppender(console);
        async.start();
        context.getLogger("com.example.test").addAppender(async);

        var report = MaskingAppenderInstaller.install(context);

        assertThat(report.wrapped()).isEqualTo(1);
        assertThat(console.getEncoder()).isInstanceOf(MaskingEncoder.class);
        async.stop();
    }

    @Test
    @DisplayName("LogstashTcpSocketAppender: an encoder outside OutputStreamAppender is wrapped, JSON stays valid")
    void install_whenLogstashTcpAppenderHasEncoder_shouldWrapItAndKeepValidUtf8Json() throws Exception {
        MaskingLogbackSupport.useLineMasker(new LineMasker(new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()))));
        var json = new LogstashEncoder();
        json.setContext(context);
        json.start();
        var tcp = new LogstashTcpSocketAppender();
        tcp.setContext(context);
        tcp.setEncoder(json);
        var logger = context.getLogger("com.example.test");
        logger.addAppender(tcp);

        var report = MaskingAppenderInstaller.install(context);
        var bytes = tcp.getEncoder().encode(new LoggingEvent(
                Logger.class.getName(), logger, Level.INFO,
                "\u041a\u043b\u0438\u0435\u043d\u0442 \u043f\u0440\u0438\u043d\u044f\u0442, snils=12345678900", null, null));

        assertThat(report.wrapped()).isEqualTo(1);
        assertThat(report.isFullyCovered()).isTrue();
        assertThat(new ObjectMapper().readTree(bytes).get("message").asText())
                .as("The JSON encoder writes UTF-8 whatever the JVM charset")
                .isEqualTo("\u041a\u043b\u0438\u0435\u043d\u0442 \u043f\u0440\u0438\u043d\u044f\u0442, snils=***");
    }

    @Test
    @DisplayName("A composite appender that also writes itself (KafkaAppender): it and nested ones are wrapped")
    void install_whenAttachableAppenderAlsoHasOwnEncoder_shouldWrapOwnAndNestedEncoders() {
        var fallback = new ConsoleAppender<ILoggingEvent>();
        fallback.setContext(context);
        fallback.setEncoder(encoder());
        var kafka = new KafkaLikeAppender();
        kafka.setContext(context);
        kafka.setEncoder(encoder());
        kafka.addAppender(fallback);
        context.getLogger("com.example.test").addAppender(kafka);

        var report = MaskingAppenderInstaller.install(context);

        assertThat(report.wrapped()).isEqualTo(2);
        assertThat(report.isFullyCovered()).isTrue();
        assertThat(kafka.encoder).as("No getter: the encoder is found by field").isInstanceOf(MaskingEncoder.class);
        assertThat(fallback.getEncoder()).isInstanceOf(MaskingEncoder.class);
        assertThat(MaskingAppenderInstaller.install(context).alreadyWrapped()).isEqualTo(2);
    }

    @Test
    @DisplayName("An unknown appender with setEncoder is wrapped by field, no list of known classes needed")
    void install_whenUnknownAppenderHasEncoderField_shouldWrapIt() {
        var sentry = new SentryLikeAppender();
        sentry.setContext(context);
        sentry.setEncoder(encoder());
        context.getLogger("com.example.test").addAppender(sentry);

        var report = MaskingAppenderInstaller.install(context);

        assertThat(sentry.encoder).isInstanceOf(MaskingEncoder.class);
        assertThat(report.isFullyCovered()).isTrue();
        assertThat(MaskingAppenderInstaller.install(context).alreadyWrapped()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reports appenders without encoders as unsupported")
    void install_whenAppenderHasNoEncoder_shouldReportItAsUnsupportedAndNotFullyCovered() {
        var listAppender = new ListAppender<ILoggingEvent>();
        listAppender.setContext(context);
        listAppender.start();
        context.getLogger("com.example.test").addAppender(listAppender);

        var report = MaskingAppenderInstaller.install(context);

        assertThat(report.isFullyCovered()).isFalse();
        assertThat(report.unsupported()).containsExactly(ListAppender.class.getName());
    }

    @Test
    @DisplayName("Reports custom unsupported appenders too")
    void install_whenAppenderIsCustomImplementation_shouldReportItAsUnsupported() {
        var custom = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                // Does not write output.
            }
        };
        custom.setContext(context);
        custom.start();
        context.getLogger("com.example.test").addAppender(custom);

        assertThat(MaskingAppenderInstaller.install(context).unsupported()).hasSize(1);
    }

    @Test
    @DisplayName("Preserves non-ASCII text in the delegate charset")
    void install_whenDelegateEncoderUsesUtf8_shouldKeepCharsetInMaskedOutput() {
        MaskingLogbackSupport.useLineMasker(new LineMasker(new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()))));
        var sink = new ByteArrayOutputStream();
        var logger = context.getLogger("com.example.test");
        logger.addAppender(streamAppender(sink));

        MaskingAppenderInstaller.install(context);
        logger.info("Customer registered, snils=12345678900");

        assertThat(sink.toString(StandardCharsets.UTF_8)).contains("Customer registered").contains("snils=***");
    }

    private PatternLayoutEncoder encoder() {
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        return encoder;
    }

    private OutputStreamAppender<ILoggingEvent> streamAppender(ByteArrayOutputStream sink) {
        var appender = new OutputStreamAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setName("STREAM");
        appender.setEncoder(encoder());
        appender.setOutputStream(sink);
        appender.start();
        return appender;
    }

    /**
     * Shaped like {@code SentryAppender}: the encoder sits in a private field and renders only the message
     * text, while arguments, MDC and the exception are sent separately.
     */
    public static class SentryLikeAppender extends AppenderBase<ILoggingEvent> {

        private Encoder<ILoggingEvent> encoder;

        public void setEncoder(Encoder<ILoggingEvent> encoder) {
            this.encoder = encoder;
        }

        @Override
        protected void append(ILoggingEvent event) {
            encoder.encode(event);
            event.getArgumentArray();
        }
    }

    /**
     * Shaped like {@code KafkaAppender} from logback-kafka-appender: it writes the main stream itself,
     * nested appenders receive only what was not sent, and the encoder sits in a protected field without a
     * getter.
     */
    public static class KafkaLikeAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
            implements AppenderAttachable<ILoggingEvent> {

        private final AppenderAttachableImpl<ILoggingEvent> fallback = new AppenderAttachableImpl<>();
        protected Encoder<ILoggingEvent> encoder;

        public void setEncoder(Encoder<ILoggingEvent> encoder) {
            this.encoder = encoder;
        }

        @Override
        protected void append(ILoggingEvent event) {
            encoder.encode(event);
        }

        @Override
        public void addAppender(Appender<ILoggingEvent> appender) {
            fallback.addAppender(appender);
        }

        @Override
        public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
            return fallback.iteratorForAppenders();
        }

        @Override
        public Appender<ILoggingEvent> getAppender(String name) {
            return fallback.getAppender(name);
        }

        @Override
        public boolean isAttached(Appender<ILoggingEvent> appender) {
            return fallback.isAttached(appender);
        }

        @Override
        public void detachAndStopAllAppenders() {
            fallback.detachAndStopAllAppenders();
        }

        @Override
        public boolean detachAppender(Appender<ILoggingEvent> appender) {
            return fallback.detachAppender(appender);
        }

        @Override
        public boolean detachAppender(String name) {
            return fallback.detachAppender(name);
        }
    }
}

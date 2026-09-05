package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
}

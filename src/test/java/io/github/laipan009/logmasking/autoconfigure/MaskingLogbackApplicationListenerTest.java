package io.github.laipan009.logmasking.autoconfigure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller.Report;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller.Source;
import io.github.laipan009.logmasking.adapter.logback.MaskingEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener modifies the global Logback context, so the positive case runs last. Earlier negative
 * cases run before installation; the installer has no inverse operation.
 * <p>Refresh cases run after it: removing a wrapper by hand simulates a Logback rebuild.
 */
@DisplayName("MaskingLogbackApplicationListener: switches and ordering")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MaskingLogbackApplicationListenerTest {

    private final MaskingLogbackApplicationListener listener = new MaskingLogbackApplicationListener();

    @Test
    @Order(1)
    @DisplayName("Runs immediately after Boot's logging listener, once appenders exist")
    void getOrder_whenListenerCreated_shouldReturnOrderRightAfterBootLoggingListener() {
        assertThat(MaskingLogbackApplicationListener.ORDER).isEqualTo(LoggingApplicationListener.DEFAULT_ORDER + 1);
        assertThat(listener.getOrder()).isEqualTo(MaskingLogbackApplicationListener.ORDER);
    }

    @Test
    @Order(2)
    @DisplayName("mode=off leaves encoders unchanged")
    void onApplicationEvent_whenMaskingModeIsOff_shouldNotWrapRootEncoder() {
        listener.onApplicationEvent(eventWith("logging.masking.mode", "off"));

        assertThat(rootEncoderIsMasking()).isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("enabled=false leaves encoders unchanged")
    void onApplicationEvent_whenMaskingDisabled_shouldNotWrapRootEncoder() {
        listener.onApplicationEvent(eventWith("logging.masking.enabled", "false"));

        assertThat(rootEncoderIsMasking()).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("Wraps root logger encoders idempotently by default")
    void onApplicationEvent_whenNoMaskingPropertiesSet_shouldWrapRootEncoderOnceAndStayIdempotent() {
        listener.onApplicationEvent(eventWith("unrelated.property", "value"));
        assertThat(rootEncoderIsMasking()).isTrue();

        var encoder = rootEncoder();
        listener.onApplicationEvent(eventWith("unrelated.property", "value"));

        assertThat(rootEncoder()).as("A second pass does not wrap the wrapper").isSameAs(encoder);
    }

    @Test
    @Order(5)
    @DisplayName("Started without masking: a refresh does not install wrappers even if the flag is now true")
    void onApplicationEvent_whenRefreshedAfterStartWithoutMasking_shouldNotWrapEncoder() {
        unwrapRootEncoder();

        listener.onApplicationEvent(refreshOf(false, "logging.masking.enabled", "true"));

        assertThat(rootEncoderIsMasking()).isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("A temporary refresh context does not decide for the application: it lacks application.yml")
    void onApplicationEvent_whenEnvironmentBelongsToRefreshTemporaryContext_shouldNotWrapEncoder() {
        unwrapRootEncoder();
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("refreshArgs", Map.of()));

        listener.onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment));

        assertThat(rootEncoderIsMasking()).isFalse();
    }

    @Test
    @Order(7)
    @DisplayName("Started with masking: a refresh restores wrappers even if the flag is now false")
    void onApplicationEvent_whenRefreshedAfterStartWithMasking_shouldWrapRebuiltEncoderAgain() {
        unwrapRootEncoder();

        listener.onApplicationEvent(refreshOf(true, "logging.masking.enabled", "false"));

        assertThat(rootEncoderIsMasking()).isTrue();
    }

    @Test
    @DisplayName("A refresh wrapped nothing but the source failed to attach: a WARN, not silence")
    void describe_whenSourceFailedAfterRefreshWithoutChanges_shouldWarn() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MaskingLogbackApplicationListener.class);
        var log = new ListAppender<ILoggingEvent>();
        log.setContext(logger.getLoggerContext());
        log.start();
        logger.addAppender(log);
        try {
            MaskingLogbackApplicationListener.describe(new Report(0, 1, List.of(), Source.FAILED), false, true);
            MaskingLogbackApplicationListener.describe(new Report(0, 1, List.of(), Source.PRESENT), false, true);
        } finally {
            logger.detachAppender(log);
        }

        assertThat(log.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("could not be attached to the event source");
        });
    }

    private static ApplicationEnvironmentPreparedEvent eventWith(String key, String value) {
        ConfigurableEnvironment environment = new MockEnvironment().withProperty(key, value);
        return new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment);
    }

    /** The Logback layer exists in the context only if masking was enabled at startup. */
    private static EnvironmentChangeEvent refreshOf(boolean maskedSinceStartup, String key, String value) {
        var context = new GenericApplicationContext();
        context.setEnvironment(new MockEnvironment().withProperty(key, value));
        if (maskedSinceStartup) {
            context.registerBean(MaskingLogbackAutoConfiguration.class);
        }
        context.refresh();
        return new EnvironmentChangeEvent(context, Set.of(key));
    }

    /** What an appender looks like after a Logback rebuild: back to its own encoder. */
    private static void unwrapRootEncoder() {
        var appender = rootStreamAppender();
        if (appender.getEncoder() instanceof MaskingEncoder masking) {
            appender.setEncoder(masking.delegate());
        }
    }

    private static boolean rootEncoderIsMasking() {
        return rootEncoder() instanceof MaskingEncoder;
    }

    private static Object rootEncoder() {
        var appender = rootStreamAppender();
        return appender == null ? null : appender.getEncoder();
    }

    private static OutputStreamAppender<ILoggingEvent> rootStreamAppender() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var appenders = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        while (appenders.hasNext()) {
            if (appenders.next() instanceof OutputStreamAppender<ILoggingEvent> stream) {
                return stream;
            }
        }
        return null;
    }
}

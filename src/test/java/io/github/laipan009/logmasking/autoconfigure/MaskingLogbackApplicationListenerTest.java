package io.github.laipan009.logmasking.autoconfigure;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;
import io.github.laipan009.logmasking.adapter.logback.MaskingEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener modifies the global Logback context, so the positive case runs last. Earlier negative
 * cases run before installation; the installer has no inverse operation.
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

    private static ApplicationEnvironmentPreparedEvent eventWith(String key, String value) {
        ConfigurableEnvironment environment = new MockEnvironment().withProperty(key, value);
        return new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment);
    }

    private static boolean rootEncoderIsMasking() {
        return rootEncoder() instanceof MaskingEncoder;
    }

    private static Object rootEncoder() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var appenders = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        while (appenders.hasNext()) {
            if (appenders.next() instanceof OutputStreamAppender<ILoggingEvent> stream) {
                return stream.getEncoder();
            }
        }
        return null;
    }
}

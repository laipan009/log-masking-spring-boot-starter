package io.github.laipan009.logmasking.autoconfigure;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.spi.ConfigurationEvent;
import ch.qos.logback.core.spi.ConfigurationEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.bootstrap.config.PropertySourceLocator;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import io.github.laipan009.logmasking.adapter.logback.MaskingEncoder;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Spring Cloud refresh with bootstrap starts a temporary context, and {@code PropertySourceBootstrapConfiguration}
 * rebuilds Logback in it when the environment has at least one {@code logging.*} key. The new appenders come with
 * their own encoders, which is how masking disappeared in a deployed environment until the pod restarted.
 * <p>The config server here is the {@link ConfigServerStub} stub, registered through
 * {@code spring.cloud.bootstrap.sources}: a shared test {@code spring.factories} would hand it to every test with
 * bootstrap. The test changes its response between startup and refresh, as a real config server would.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Masking survives a Spring Cloud refresh")
class MaskingAfterRefreshIT {

    @BeforeEach
    void resetConfigServer() {
        ConfigServerStub.CONFIG.clear();
    }

    @Test
    @DisplayName("Refresh rebuilt Logback: wrappers are installed again, sensitive data stays out of stdout")
    void refresh_whenLogbackIsRebuilt_shouldWrapNewEncodersAgain(CapturedOutput output) {
        ConfigServerStub.CONFIG.put("logging.level.com.example", "INFO");
        try (var context = run(new StandardEnvironment())) {
            var before = rootStreamAppender();
            assertThat(before.getEncoder()).isInstanceOf(MaskingEncoder.class);

            refresh(context);

            var after = rootStreamAppender();
            assertThat(after).as("Refresh actually rebuilt Logback").isNotSameAs(before);
            assertThat(after.getEncoder()).isInstanceOf(MaskingEncoder.class);
            assertMaskedAtSource();
            assertThat(output).contains("snils=***").doesNotContain("12345678900");
        }
    }

    @Test
    @DisplayName("logback-spring.xml: wrappers return when Logback configuration ends, before EnvironmentChangeEvent")
    void refresh_whenLogbackIsRebuiltFromXml_shouldWrapEncodersAsSoonAsConfigurationEnds(CapturedOutput output) {
        ConfigServerStub.CONFIG.put("logging.level.com.example", "INFO");
        try (var context = run(new StandardEnvironment(), "logging.config=classpath:refresh-it/logback-spring.xml")) {
            var before = rootStreamAppender();
            // Registered after ours, so at configuration end it sees what ours has already done
            var encoderWhenConfigured = new ArrayList<Boolean>();
            ConfigurationEventListener probe = event -> {
                if (event.getEventType() == ConfigurationEvent.EventType.CONFIGURATION_ENDED_SUCCESSFULLY) {
                    encoderWhenConfigured.add(rootStreamAppender().getEncoder() instanceof MaskingEncoder);
                }
            };
            loggerContext().addConfigurationEventListener(probe);
            var startupLogLength = output.length();
            try {
                refresh(context);
            } finally {
                loggerContext().removeConfigurationEventListener(probe);
            }

            assertThat(rootStreamAppender()).as("Refresh actually rebuilt Logback").isNotSameAs(before);
            assertThat(encoderWhenConfigured).as("The encoder is already masking when configuration ends")
                    .isNotEmpty().containsOnly(true);
            assertMaskedAtSource();
            assertThat(output).contains("[xml] ").contains("snils=***").doesNotContain("12345678900");
            var refreshLog = output.toString().substring(startupLogLength);
            assertThat(refreshLog.indexOf("Log masking is attached to"))
                    .as("Spring suppresses logging on reconfiguration, yet the masking line appears after refresh")
                    .isNotNegative()
                    .isLessThan(refreshLog.indexOf("Refresh keys changed"));
        }
    }

    @Test
    @DisplayName("Disabling masking in the config server at runtime: refresh keeps it, the decision is made at startup")
    void refresh_whenMaskingDisabledAfterStart_shouldKeepMasking(CapturedOutput output) {
        ConfigServerStub.CONFIG.put("logging.level.com.example", "INFO");
        try (var context = run(new StandardEnvironment())) {
            var before = rootStreamAppender();
            ConfigServerStub.CONFIG.put("logging.masking.enabled", "false");

            refresh(context);

            assertThat(rootStreamAppender()).as("Refresh actually rebuilt Logback").isNotSameAs(before);
            assertThat(rootStreamAppender().getEncoder()).isInstanceOf(MaskingEncoder.class);
            assertThat(output).contains("snils=***").doesNotContain("12345678900");
        }
    }

    @Test
    @DisplayName("Started unmasked: refresh keeps masking off, though the temporary context cannot see application.yml")
    void refresh_whenMaskingDisabledInApplicationYml_shouldNotWrapEncoders() {
        ConfigServerStub.CONFIG.put("demo.key", "1");
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yml]'", Map.of("logging.masking.enabled", "false")));
        try (var context = run(environment)) {
            assertThat(rootStreamAppender().getEncoder()).isNotInstanceOf(MaskingEncoder.class);

            refresh(context);

            assertThat(rootStreamAppender().getEncoder()).isNotInstanceOf(MaskingEncoder.class);
        }
    }

    private static ConfigurableApplicationContext run(StandardEnvironment environment, String... properties) {
        return new SpringApplicationBuilder(RefreshableApplication.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .properties("spring.cloud.bootstrap.enabled=true", "spring.main.banner-mode=off",
                        "spring.cloud.bootstrap.sources=" + ConfigServerStub.class.getName())
                .properties(properties)
                .run();
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static void refresh(ConfigurableApplicationContext context) {
        context.publishEvent(new RefreshEvent(context, null, "config refresh"));
        LoggerFactory.getLogger(MaskingAfterRefreshIT.class).info("after refresh: CustomerDto(snils=12345678900)");
    }

    /** {@code reset()} on reconfiguration also removes turbo filters: the source mask must come back first. */
    private static void assertMaskedAtSource() {
        assertThat(loggerContext().getTurboFilterList()).as("The source mask is back and comes first")
                .first().extracting(TurboFilter::getName).isEqualTo("log-masking");
    }

    private static OutputStreamAppender<ILoggingEvent> rootStreamAppender() {
        var appenders = loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        while (appenders.hasNext()) {
            if (appenders.next() instanceof OutputStreamAppender<ILoggingEvent> stream) {
                return stream;
            }
        }
        throw new AssertionError("The root logger has no OutputStreamAppender");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({RefreshAutoConfiguration.class, JacksonAutoConfiguration.class,
            MaskingAutoConfiguration.class, MaskingLogbackAutoConfiguration.class})
    static class RefreshableApplication {
    }

    /** Config server response; a {@code logging.*} key in it makes refresh rebuild Logback. */
    @Configuration(proxyBeanMethods = false)
    static class ConfigServerStub {

        static final Map<String, Object> CONFIG = new ConcurrentHashMap<>();

        @Bean
        PropertySourceLocator configServer() {
            return environment -> new MapPropertySource("configServer", Map.copyOf(CONFIG));
        }
    }
}

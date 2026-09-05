package io.github.laipan009.logmasking.autoconfigure;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller;
import io.github.laipan009.logmasking.autoconfigure.MaskingProperties.Mode;

/**
 * Installs masking encoders immediately after Boot initializes Logback.
 * <p>{@code LoggingApplicationListener} runs at {@code HIGHEST_PRECEDENCE + 20} on the environment-
 * prepared event. This listener uses {@code + 21}: earlier appenders do not exist, later startup lines
 * could escape masking.
 * <p>An idempotent second pass on {@code ApplicationPreparedEvent} handles logging reinitialization,
 * for example after a profile change.
 * <p>Output before Logback initialization, including the banner and earliest environment preparation,
 * is not covered.
 */
public class MaskingLogbackApplicationListener implements ApplicationListener<ApplicationEvent>, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 21;

    private static final String LOGGER_CONTEXT = "ch.qos.logback.classic.LoggerContext";

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
            install(prepared.getEnvironment());
        } else if (event instanceof ApplicationPreparedEvent prepared) {
            install(prepared.getApplicationContext().getEnvironment());
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private void install(Environment environment) {
        if (!enabled(environment) || !ClassUtils.isPresent(LOGGER_CONTEXT, getClass().getClassLoader())) {
            return;
        }
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        var report = MaskingAppenderInstaller.install(context);
        if (report.wrapped() == 0 && report.isFullyCovered()) {
            return;
        }
        var log = LoggerFactory.getLogger(MaskingLogbackApplicationListener.class);
        if (report.wrapped() > 0) {
            log.info("Log masking is attached to {} appender(s): every line written through SLF4J is masked",
                    report.covered());
        }
        if (!report.isFullyCovered()) {
            log.warn("Log masking is NOT applied to {}: these appenders write without an encoder, "
                    + "their output stays unmasked", report.unsupported());
        }
    }

    private boolean enabled(Environment environment) {
        if (!Boolean.TRUE.equals(environment.getProperty("logging.masking.enabled", Boolean.class, true))) {
            return false;
        }
        return Mode.parse(environment.getProperty("logging.masking.mode")) == Mode.ENCODER;
    }
}

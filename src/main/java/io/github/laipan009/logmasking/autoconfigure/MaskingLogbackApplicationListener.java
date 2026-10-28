package io.github.laipan009.logmasking.autoconfigure;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller;
import io.github.laipan009.logmasking.adapter.logback.MaskingAppenderInstaller.Report;
import io.github.laipan009.logmasking.adapter.logback.MaskingWatchdog;
import io.github.laipan009.logmasking.autoconfigure.MaskingProperties.Mode;

/**
 * Installs masking at the source and masking encoders immediately after Boot initializes Logback.
 * <p>{@code LoggingApplicationListener} runs at {@code HIGHEST_PRECEDENCE + 20} on the environment-
 * prepared event. This listener uses {@code + 21}: earlier appenders do not exist, later startup lines
 * could escape masking.
 * <p>An idempotent second pass on {@code ApplicationPreparedEvent} handles logging reinitialization,
 * for example after a profile change.
 * <p>A third pass runs after a Spring Cloud refresh ({@code EnvironmentChangeEvent}). With bootstrap, a
 * refresh starts a temporary context that rebuilds Logback without the wrappers (see the README for
 * when). Without this pass masking would be lost until a restart. The event is recognized by class name,
 * so the starter does not need spring-cloud-context. With bootstrap the event also reaches the parent
 * bootstrap context and its listener instance; that second pass finds everything wrapped and stays
 * silent. Logback configured from XML is already wrapped by this pass through the configuration event
 * ({@code MaskingWatchdog}), but Spring silenced the line about it, so this pass writes it.
 * <p>Whether to mask is decided once, at application startup, and a refresh does not revisit it: the
 * third pass restores what was decided at startup and does not re-read {@code enabled}. Otherwise
 * masking enabled at runtime would use the fallback list, since the layer's beans did not exist at
 * startup, and masking disabled at runtime would be removed only if Logback happened to be rebuilt.
 * Temporary refresh contexts decide nothing either: the listener reaches them through
 * {@code spring.factories}, but their environment lacks {@code application.yml}, so masking disabled
 * there would be enabled. They are recognized by the {@code refreshArgs} property source, which Spring
 * Cloud also uses to disable {@code LoggingApplicationListener} in them.
 * <p>Rebuilds outside these events, such as {@code scan="true"} or an appender added by code, are
 * caught by {@code MaskingWatchdog}.
 * <p>Output before Logback initialization, including the banner and earliest environment preparation,
 * is not covered. Neither are lines from other threads in the milliseconds between a Logback rebuild on
 * refresh and the third pass, when Logback is configured without XML.
 */
public class MaskingLogbackApplicationListener implements ApplicationListener<ApplicationEvent>, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 21;

    private static final String LOGGER_CONTEXT = "ch.qos.logback.classic.LoggerContext";

    private static final String ENVIRONMENT_CHANGE_EVENT =
            "org.springframework.cloud.context.environment.EnvironmentChangeEvent";

    private static final String REFRESH_ARGS = "refreshArgs";

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
            installOnStartup(prepared.getEnvironment());
        } else if (event instanceof ApplicationPreparedEvent prepared) {
            installOnStartup(prepared.getApplicationContext().getEnvironment());
        } else if (ENVIRONMENT_CHANGE_EVENT.equals(event.getClass().getName())
                && event.getSource() instanceof ApplicationContext context
                && maskedSinceStartup(context)) {
            install(true);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private void installOnStartup(ConfigurableEnvironment environment) {
        if (!environment.getPropertySources().contains(REFRESH_ARGS) && enabled(environment)) {
            install(false);
        }
    }

    /** The Logback layer exists in the context only if masking was enabled at startup. */
    private static boolean maskedSinceStartup(ApplicationContext context) {
        return context.getBeanNamesForType(MaskingLogbackAutoConfiguration.class, false, false).length > 0;
    }

    private void install(boolean afterRefresh) {
        if (!ClassUtils.isPresent(LOGGER_CONTEXT, getClass().getClassLoader())) {
            return;
        }
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        var report = MaskingAppenderInstaller.install(context);
        // The counter is reset on every pass after a refresh, even if this pass wrapped something itself.
        var unreported = afterRefresh ? MaskingWatchdog.takeUnreported() : 0;
        describe(report, report.changed() || unreported > 0, afterRefresh);
    }

    static void describe(Report report, boolean changed, boolean afterRefresh) {
        // A refresh without a Logback rebuild changes nothing, and uncovered appenders were reported at
        // startup. A source that failed to attach is always reported: the pass may have wrapped nothing.
        if (!changed && report.isMaskedAtSource() && (report.isFullyCovered() || afterRefresh)) {
            return;
        }
        var log = LoggerFactory.getLogger(MaskingLogbackApplicationListener.class);
        if (!report.isMaskedAtSource()) {
            log.warn("Log masking could not be attached to the event source: appenders without an encoder "
                    + "and Java agents receive events unmasked, only encoder output is masked");
        } else if (changed) {
            log.info("Log masking is attached to {} appender(s) and to the event source: every event written "
                    + "through SLF4J is masked before appenders and Java agents read it", report.covered());
        }
        if (report.unsupported().isEmpty()) {
            return;
        }
        if (report.isMaskedAtSource()) {
            log.info("Log masking reaches {} only through events masked at the source: these appenders "
                    + "write without an encoder, and what they add on their own is not masked",
                    report.unsupported());
        } else {
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

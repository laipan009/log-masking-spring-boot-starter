package io.github.laipan009.logmasking.autoconfigure;

import ch.qos.logback.classic.PatternLayout;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import io.github.laipan009.logmasking.adapter.logback.MaskingLogbackSupport;
import io.github.laipan009.logmasking.adapter.logback.MaskingWatchdog;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

/**
 * Supplies application-context rules to masking at the source and to encoder wrappers. Before this point
 * they use generic defaults only; this adds configured fields, strategy groups and the annotation index.
 * It also starts the periodic recheck of the source filter and the wrappers.
 */
@AutoConfiguration(after = MaskingAutoConfiguration.class)
@ConditionalOnClass(PatternLayout.class)
@ConditionalOnProperty(prefix = "logging.masking", name = "enabled", matchIfMissing = true)
@ConditionalOnExpression("!'off'.equalsIgnoreCase('${logging.masking.mode:encoder}')")
public class MaskingLogbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LineMasker lineMasker(MaskingPolicyRegistry policyRegistry) {
        return new LineMasker(policyRegistry);
    }

    @Bean
    InitializingBean maskingLogbackInitializer(LineMasker lineMasker) {
        return () -> MaskingLogbackSupport.useLineMasker(lineMasker);
    }

    /**
     * The thread lives as long as the context: when the context closes, the recheck stops. It does not
     * re-read {@code enabled}: the bean exists only if masking was enabled at startup.
     */
    @Bean(destroyMethod = "close")
    MaskingWatchdog maskingWatchdog(MaskingProperties properties) {
        return MaskingWatchdog.start(properties.getRecheckInterval());
    }
}

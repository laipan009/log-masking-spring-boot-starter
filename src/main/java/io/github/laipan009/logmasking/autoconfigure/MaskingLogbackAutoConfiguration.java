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
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

/**
 * Supplies application-context rules to encoder wrappers. Before this point they use generic defaults
 * only; this adds configured fields, strategy groups and the annotation index.
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
}

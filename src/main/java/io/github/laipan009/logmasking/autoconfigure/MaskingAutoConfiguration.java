package io.github.laipan009.logmasking.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LogMasker;
import io.github.laipan009.logmasking.core.MaskFieldIndex;
import io.github.laipan009.logmasking.core.MaskingPolicy;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;
import io.github.laipan009.logmasking.core.PayloadSanitizer;

import java.util.ArrayList;
import java.util.List;

@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass({ObjectMapper.class, LogMasker.class})
@ConditionalOnProperty(prefix = "logging.masking", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MaskingProperties.class)
public class MaskingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PropertiesMaskingPolicy.class)
    PropertiesMaskingPolicy propertiesMaskingPolicy(MaskingProperties properties) {
        return new PropertiesMaskingPolicy(properties);
    }

    /**
     * Registers the annotation field index as a regular {@link MaskingPolicy}, allowing rendered lines to
     * use annotation-derived names when the original object is no longer available.
     */
    @Bean
    @ConditionalOnMissingBean
    MaskFieldIndex maskFieldIndex(ObjectMapper objectMapper,
                                  MaskingProperties properties,
                                  BeanFactory beanFactory) {
        var packages = properties.getScanPackages().isEmpty()
                ? autoConfigurationPackages(beanFactory)
                : properties.getScanPackages();
        return new MaskFieldIndexScanner(objectMapper, ClassUtils.getDefaultClassLoader()).scan(packages);
    }

    @Bean
    @ConditionalOnMissingBean
    MaskingPolicyRegistry maskingPolicyRegistry(
            ObjectProvider<MaskingPolicy> policyProvider,
            MaskingProperties properties) {
        var policies = new ArrayList<MaskingPolicy>();
        if (properties.isUseDefaultFields()) {
            policies.add(new DefaultMaskingPolicy());
        }
        policyProvider.orderedStream().forEach(policies::add);
        return new MaskingPolicyRegistry(policies);
    }

    @Bean
    @ConditionalOnMissingBean
    PayloadSanitizer payloadSanitizer(ObjectMapper objectMapper,
                                      MaskingPolicyRegistry policyRegistry,
                                      MaskingProperties properties) {
        return new PayloadSanitizer(objectMapper, policyRegistry,
                Math.toIntExact(properties.getMaxBodySize().toBytes()));
    }

    @Bean
    @ConditionalOnMissingBean
    LogMasker logMasker(PayloadSanitizer sanitizer,
                        MaskingPolicyRegistry policyRegistry,
                        ObjectMapper objectMapper) {
        return new LogMasker(sanitizer, policyRegistry,
                objectMapper.getSerializationConfig().getPropertyNamingStrategy());
    }

    private static List<String> autoConfigurationPackages(BeanFactory beanFactory) {
        return AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
    }
}

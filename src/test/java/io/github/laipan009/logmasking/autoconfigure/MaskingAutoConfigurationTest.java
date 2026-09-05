package io.github.laipan009.logmasking.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.laipan009.logmasking.core.LogMasker;
import io.github.laipan009.logmasking.core.MaskingPolicy;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaskingAutoConfiguration.class))
            .withUserConfiguration(ObjectMapperConfiguration.class);

    @Test
    void whenEnabledByDefault_shouldCreateMaskingInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LogMasker.class);
            assertThat(context).hasSingleBean(MaskingPolicyRegistry.class);
            assertThat(context).hasSingleBean(PropertiesMaskingPolicy.class);
        });
    }

    @Test
    void whenDisabled_shouldNotCreateMaskingInfrastructure() {
        contextRunner
                .withPropertyValues("logging.masking.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LogMasker.class));
    }

    @Test
    void whenAdditionalFieldsConfigured_shouldMergePropertiesPolicy() {
        contextRunner
                .withPropertyValues(
                        "logging.masking.use-default-fields=false",
                        "logging.masking.additional-masked-fields=localSecret",
                        "logging.masking.additional-secret-headers=X-Local-Token")
                .run(context -> {
                    var masker = context.getBean(LogMasker.class);

                    assertThat(masker.maskJson("{\"localSecret\":\"value\",\"visible\":\"value\"}"))
                            .isEqualTo("{\"localSecret\":\"***\",\"visible\":\"value\"}");
                    assertThat(context.getBean(MaskingPolicyRegistry.class).isSecretHeader("x-local-token"))
                            .as("A configured secret header reaches the encoder through the registry")
                            .isTrue();
                });
    }

    @Test
    void whenCustomPolicyBeanProvided_shouldAggregateItWithoutReplacingPropertiesPolicy() {
        contextRunner
                .withUserConfiguration(CustomPolicyConfiguration.class)
                .withPropertyValues(
                        "logging.masking.use-default-fields=false",
                        "logging.masking.additional-masked-fields=propertySecret")
                .run(context -> {
                    var registry = context.getBean(MaskingPolicyRegistry.class);

                    assertThat(registry.isMaskedField("propertySecret")).isTrue();
                    assertThat(registry.isMaskedField("customSecret")).isTrue();
                });
    }

    @Test
    @DisplayName("use-default-fields=false removes default names from the policy")
    void whenDefaultFieldsDisabled_shouldStopMaskingBuiltInNames() {
        contextRunner.run(context -> assertThat(context.getBean(LogMasker.class)
                        .maskJson("{\"snils\":\"12345678900\"}"))
                .as("Default policy masks a built-in name")
                .isEqualTo("{\"snils\":\"***\"}"));

        contextRunner
                .withPropertyValues("logging.masking.use-default-fields=false")
                .run(context -> assertThat(context.getBean(LogMasker.class)
                                .maskJson("{\"snils\":\"12345678900\"}"))
                        .as("With defaults disabled there is no matching field")
                        .isEqualTo("{\"snils\":\"12345678900\"}"));
    }

    @Test
    @DisplayName("max-body-size omits oversized payloads and masks smaller bodies")
    void whenBodyExceedsMaxSize_shouldOmitIt() {
        contextRunner
                .withPropertyValues("logging.masking.max-body-size=64B")
                .run(context -> {
                    var masker = context.getBean(LogMasker.class);

                    assertThat(masker.maxBodyBytes()).isEqualTo(64);
                    assertThat(masker.maskJson("{\"snils\":\"12345678900\"}"))
                            .as("Smaller bodies are masked normally")
                            .isEqualTo("{\"snils\":\"***\"}");
                    assertThat(masker.maskJson("{\"snils\":\"12345678900\",\"note\":\"" + "x".repeat(80) + "\"}"))
                            .as("Oversized bodies are omitted entirely")
                            .isEqualTo("body of more than 64 bytes omitted");
                });
    }

    @Test
    @DisplayName("Configured strategies reach LogMasker as well as the registry")
    void whenFieldStrategiesConfigured_shouldApplyThemOnObjectPath() {
        contextRunner
                .withPropertyValues(
                        "logging.masking.use-default-fields=false",
                        "logging.masking.field-strategies.last4=contractNumber",
                        "logging.masking.field-strategies.hash=fullName")
                .run(context -> assertThat(context.getBean(LogMasker.class)
                        .maskJson("{\"contractNumber\":\"CN-2024-007345\",\"fullName\":\"John Smith\"}"))
                        .isEqualTo("{\"contractNumber\":\"***7345\",\"fullName\":\"#ef61a579\"}"));
    }

    @Test
    @DisplayName("Unknown mode fails startup instead of silently selecting a mode")
    void whenModeIsUnknown_shouldRejectConfiguration() {
        contextRunner
                .withPropertyValues("logging.masking.mode=partial")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void whenMaxBodySizeIsInvalid_shouldRejectConfiguration() {
        contextRunner
                .withPropertyValues("logging.masking.max-body-size=0B")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class ObjectMapperConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPolicyConfiguration {

        @Bean
        MaskingPolicy customMaskingPolicy() {
            return new MaskingPolicy() {
                @Override
                public Set<String> maskedFields() {
                    return Set.of("customSecret");
                }
            };
        }
    }
}

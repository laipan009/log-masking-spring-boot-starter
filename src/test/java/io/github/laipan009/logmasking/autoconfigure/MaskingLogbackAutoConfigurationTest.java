package io.github.laipan009.logmasking.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.laipan009.logmasking.adapter.logback.MaskingLogbackSupport;
import io.github.laipan009.logmasking.adapter.logback.MaskingWatchdog;
import io.github.laipan009.logmasking.core.LineMasker;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaskingLogbackAutoConfiguration")
class MaskingLogbackAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MaskingAutoConfiguration.class, MaskingLogbackAutoConfiguration.class))
            .withUserConfiguration(ObjectMapperConfiguration.class);

    @AfterEach
    void resetStaticHolder() {
        MaskingLogbackSupport.useLineMasker(null);
    }

    @Test
    @DisplayName("Encoder integration is enabled by default for supported appenders")
    void lineMasker_whenNoPropertiesSet_shouldBeRegisteredByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(LineMasker.class));
    }

    @Test
    @DisplayName("@Mask(scope = ALL) contributes a global name to encoder masking")
    void lineMasker_whenScannedPackageHasMaskAnnotatedField_shouldMaskOnlyThatField() {
        contextRunner
                .withPropertyValues("logging.masking.scan-packages="
                        + "io.github.laipan009.logmasking.index.fixture")
                .run(context -> assertThat(context.getBean(LineMasker.class)
                        .mask("dto=Contacts(homePhone=+79161234567, position=engineer)"))
                        .contains("homePhone=***")
                        .contains("position=engineer"));
    }

    @Test
    @DisplayName("Configured additional fields reach the encoder")
    void lineMasker_whenAdditionalMaskedFieldsConfigured_shouldMaskOnlyListedField() {
        contextRunner
                .withPropertyValues("logging.masking.additional-masked-fields=personId")
                .run(context -> assertThat(context.getBean(LineMasker.class)
                        .mask("dto=CustomerDto(personId=778899, requestId=42)"))
                        .contains("personId=***")
                        .contains("requestId=42"));
    }

    @Test
    @DisplayName("Only an explicit valid off value disables the layer")
    void lineMasker_whenModeIsOff_shouldNotBeRegistered() {
        contextRunner
                .withPropertyValues("logging.masking.mode=off")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LineMasker.class)
                        .doesNotHaveBean(MaskingWatchdog.class));
    }

    @Test
    @DisplayName("The periodic wrapper recheck is enabled with the layer and uses the configured interval")
    void maskingWatchdog_whenLayerEnabled_shouldBeRegisteredWithConfiguredInterval() {
        contextRunner
                .withPropertyValues("logging.masking.recheck-interval=30s")
                .run(context -> {
                    assertThat(context).hasSingleBean(MaskingWatchdog.class);
                    assertThat(context.getBean(MaskingProperties.class).getRecheckInterval())
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    @DisplayName("A recheck interval below one second fails startup: the pass runs under a shared lock")
    void maskingWatchdog_whenRecheckIntervalBelowOneSecond_shouldFailContextStartup() {
        contextRunner
                .withPropertyValues("logging.masking.recheck-interval=500us")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("recheck-interval must be 0 (off) or at least 1s"));
    }

    @Test
    @DisplayName("A mode typo fails startup instead of silently disabling masking")
    void lineMasker_whenModeIsMisspelled_shouldFailContextStartup() {
        contextRunner
                .withPropertyValues("logging.masking.mode=offf")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("logging.masking"));
    }

    @Test
    @DisplayName("The global switch also disables this layer")
    void lineMasker_whenMaskingIsDisabled_shouldNotBeRegistered() {
        contextRunner
                .withPropertyValues(
                        "logging.masking.enabled=false",
                        "logging.masking.mode=encoder")
                .run(context -> assertThat(context).doesNotHaveBean(LineMasker.class));
    }

    @Test
    @DisplayName("Static access supplies the masker to encoders created outside Spring")
    void maskingLogbackInitializer_whenContextStarted_shouldPublishConfiguredMaskerToStaticHolder() {
        contextRunner
                .withPropertyValues(
                        "logging.masking.mode=encoder",
                        "logging.masking.additional-masked-fields=accountNumber")
                .run(context -> assertThat(MaskingLogbackSupport.maskLine("accountNumber=100500"))
                        .as("The configured masker replaces the startup fallback")
                        .isEqualTo("accountNumber=***"));
    }

    @Test
    @DisplayName("YAML strategy groups reach the encoder")
    void lineMasker_whenFieldStrategiesConfigured_shouldApplyStrategyPerField() {
        contextRunner
                .withPropertyValues(
                        "logging.masking.field-strategies.last4=contractNumber",
                        "logging.masking.field-strategies.hash=accessToken")
                .run(context -> assertThat(context.getBean(LineMasker.class)
                        .mask("doc=Data(contractNumber=TD-00012345, accessToken=Smith, id=7)"))
                        .contains("contractNumber=***2345")
                        .contains("accessToken=#")
                        .contains("id=7")
                        .doesNotContain("Smith"));
    }

    @Configuration(proxyBeanMethods = false)
    static class ObjectMapperConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}

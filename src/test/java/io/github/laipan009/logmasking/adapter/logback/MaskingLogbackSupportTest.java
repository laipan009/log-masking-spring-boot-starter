package io.github.laipan009.logmasking.adapter.logback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("MaskingLogbackSupport: fallback and failure behavior")
class MaskingLogbackSupportTest {

    @AfterEach
    void restoreConfiguredMasker() {
        MaskingLogbackSupport.useLineMasker(null);
    }

    @Test
    @DisplayName("Generic defaults mask startup logs before the context is ready")
    void maskLine_whenNoMaskerConfigured_shouldMaskWithBuiltInDefaults() {
        assertThat(MaskingLogbackSupport.maskLine("snils=12345678900")).isEqualTo("snils=***");
    }

    @Test
    @DisplayName("The configured masker replaces the fallback")
    void maskLine_whenMaskerConfigured_shouldUseConfiguredMasker() {
        MaskingLogbackSupport.useLineMasker(
                new LineMasker(new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()))));

        assertThat(MaskingLogbackSupport.maskLine("snils=12345678900")).isEqualTo("snils=***");
    }

    @Test
    @DisplayName("A masking failure passes through the original line without failing logging")
    void maskLine_whenMaskerThrowsRuntimeException_shouldReturnSameLineInstance() {
        var broken = mock(LineMasker.class);
        given(broken.mask(anyString())).willThrow(new IllegalStateException("boom"));
        MaskingLogbackSupport.useLineMasker(broken);

        var line = "snils=12345678900";

        assertThat(MaskingLogbackSupport.maskLine(line))
                .as("Preserves the original log line when masking fails")
                .isSameAs(line);
    }

    @Test
    @DisplayName("Catches LinkageError without breaking the logging thread")
    void maskLine_whenMaskerThrowsLinkageError_shouldReturnOriginalLine() {
        var broken = mock(LineMasker.class);
        given(broken.mask(anyString())).willThrow(new NoClassDefFoundError("missing"));
        MaskingLogbackSupport.useLineMasker(broken);

        assertThat(MaskingLogbackSupport.maskLine("snils=12345678900")).isEqualTo("snils=12345678900");
    }

    @Test
    void maskLine_whenLineIsNullOrEmpty_shouldReturnItUnchanged() {
        assertThat(MaskingLogbackSupport.maskLine(null)).isNull();
        assertThat(MaskingLogbackSupport.maskLine("")).isEmpty();
    }
}

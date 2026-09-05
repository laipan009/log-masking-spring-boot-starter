package io.github.laipan009.logmasking.autoconfigure;

import jakarta.validation.constraints.AssertTrue;
import org.slf4j.event.Level;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;
import io.github.laipan009.logmasking.core.MaskStrategy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("logging.masking")
@Validated
public class MaskingProperties {

    private boolean enabled = true;
    private boolean useDefaultFields = true;
    private Set<String> additionalMaskedFields = new LinkedHashSet<>();
    private Set<String> additionalSecretHeaders = new LinkedHashSet<>();
    /**
     * Groups field names by strategy: {@code hash} hashes values and {@code last4} exposes the tail.
     * Fields with no explicit strategy use full masking.
     */
    private Map<MaskStrategy, Set<String>> fieldStrategies = new EnumMap<>(MaskStrategy.class);

    /**
     * Packages to scan for {@code @Mask}. Empty means the {@code @SpringBootApplication} packages.
     */
    private List<String> scanPackages = new ArrayList<>();

    private DataSize maxBodySize = DataSize.ofKilobytes(64);
    private Mode mode = Mode.DEFAULT;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUseDefaultFields() {
        return useDefaultFields;
    }

    public void setUseDefaultFields(boolean useDefaultFields) {
        this.useDefaultFields = useDefaultFields;
    }

    public Set<String> getAdditionalMaskedFields() {
        return additionalMaskedFields;
    }

    public void setAdditionalMaskedFields(Set<String> additionalMaskedFields) {
        this.additionalMaskedFields = additionalMaskedFields;
    }

    public Set<String> getAdditionalSecretHeaders() {
        return additionalSecretHeaders;
    }

    public void setAdditionalSecretHeaders(Set<String> additionalSecretHeaders) {
        this.additionalSecretHeaders = additionalSecretHeaders;
    }

    public Map<MaskStrategy, Set<String>> getFieldStrategies() {
        return fieldStrategies;
    }

    public void setFieldStrategies(Map<MaskStrategy, Set<String>> fieldStrategies) {
        this.fieldStrategies = fieldStrategies;
    }


    public List<String> getScanPackages() {
        return scanPackages;
    }

    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }

    public DataSize getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(DataSize maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }


    @AssertTrue(message = "logging.masking.max-body-size must be between 1 byte and 2GB")
    public boolean isMaxBodySizeValid() {
        var bytes = maxBodySize == null ? 0 : maxBodySize.toBytes();
        return bytes > 0 && bytes <= Integer.MAX_VALUE;
    }


    public enum Mode {

        /**
         * Disables encoder integration; explicit {@code LogMasker} calls remain available.
         */
        OFF,

        /**
         * Wraps supported appender encoders without requiring logging configuration changes.
         */
        ENCODER;

        public static final Mode DEFAULT = ENCODER;

        /**
         * Parses the mode before the context and relaxed binding are available.
         * <p>Missing or unknown values use {@link #DEFAULT} so a typo cannot silently disable masking. Context
         * property binding subsequently rejects unknown values.
         */
        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT;
            }
            for (var mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return DEFAULT;
        }
    }
}

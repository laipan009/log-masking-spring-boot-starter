package io.github.laipan009.logmasking.adapter.logback;

import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.util.List;

/**
 * Holds the {@link LineMasker} used by encoder wrappers. Logback constructs encoders outside Spring,
 * so access is static. Before the context is ready, a fallback masks generic default names in startup
 * logs.
 */
public final class MaskingLogbackSupport {

    private static volatile LineMasker lineMasker;

    private MaskingLogbackSupport() {
    }

    /**
     * Installs application-context rules in place of the generic startup fallback.
     */
    public static void useLineMasker(LineMasker masker) {
        lineMasker = masker;
    }

    /**
     * Returns the original string instance when no masking is needed, allowing {@link MaskingEncoder} to
     * reuse the original bytes without transcoding.
     */
    public static String maskLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        try {
            var current = lineMasker;
            return (current == null ? FallbackHolder.FALLBACK_LINE : current).mask(line);
        } catch (RuntimeException | LinkageError e) {
            return line;
        }
    }

    private static final class FallbackHolder {

        private static final MaskingPolicyRegistry DEFAULTS =
                new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()));

        private static final LineMasker FALLBACK_LINE = new LineMasker(DEFAULTS);

        private FallbackHolder() {
        }
    }
}

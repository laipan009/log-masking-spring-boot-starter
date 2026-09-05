package io.github.laipan009.logmasking.core;

import java.util.Map;
import java.util.Set;

/**
 * Contributes application masking rules alongside defaults, annotations and configuration. Sources are
 * combined, not replaced.
 * <p>Use configuration for fixed lists. A bean is useful for computed lists, contextual rules via
 * {@link #contextualMaskedFields(Set)}, or a shared companion library that supplies rules to several
 * applications.
 * <p>All methods have defaults. Override only those needed. Conflicting explicit strategies resolve to
 * the more restrictive option, independently of bean order.
 */
public interface MaskingPolicy {

    default Set<String> maskedFields() {
        return Set.of();
    }

    default Set<String> secretHeaders() {
        return Set.of();
    }

    /**
     * Assigns field strategies, for example {@code snils -> LAST4}. Each name also joins the masked name
     * set, just like {@link #maskedFields()}.
     */
    default Map<String, MaskStrategy> fieldStrategies() {
        return Map.of();
    }

    /**
     * Returns names that are sensitive only beside particular siblings, such as {@code city} beside {@code
     * birthPlace}. The sibling set is case-insensitive. Applies only to the object/JSON path through
     * {@link LogMasker}; rendered lines have no sibling context.
     */
    default Set<String> contextualMaskedFields(Set<String> siblingFields) {
        return Set.of();
    }

}

package io.github.laipan009.logmasking.autoconfigure;

import io.github.laipan009.logmasking.core.MaskStrategy;
import io.github.laipan009.logmasking.core.MaskingPolicy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class PropertiesMaskingPolicy implements MaskingPolicy {

    private final MaskingProperties properties;

    public PropertiesMaskingPolicy(MaskingProperties properties) {
        this.properties = properties;
    }

    @Override
    public Set<String> maskedFields() {
        return Set.copyOf(properties.getAdditionalMaskedFields());
    }

    /**
     * Inverts YAML strategy groups into the internal field-name-to-strategy map.
     */
    @Override
    public Map<String, MaskStrategy> fieldStrategies() {
        var byField = new HashMap<String, MaskStrategy>();
        properties.getFieldStrategies().forEach(
                (strategy, fields) -> fields.forEach(field -> byField.put(field, strategy)));
        return Map.copyOf(byField);
    }

    @Override
    public Set<String> secretHeaders() {
        return Set.copyOf(properties.getAdditionalSecretHeaders());
    }

}

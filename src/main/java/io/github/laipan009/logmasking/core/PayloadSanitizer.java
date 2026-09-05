package io.github.laipan009.logmasking.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PayloadSanitizer {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final String OMITTED = "body omitted";

    private final ObjectMapper objectMapper;
    private final MaskingPolicyRegistry policy;
    private final int maxBodyBytes;

    public PayloadSanitizer(ObjectMapper objectMapper, MaskingPolicyRegistry policy) {
        this(objectMapper, policy, MAX_BODY_BYTES);
    }

    public PayloadSanitizer(ObjectMapper objectMapper, MaskingPolicyRegistry policy, int maxBodyBytes) {
        this.objectMapper = objectMapper.copy();
        this.policy = policy;
        this.maxBodyBytes = maxBodyBytes;
    }

    public String sanitizeJson(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > maxBodyBytes) {
            return "body of more than " + maxBodyBytes + " bytes omitted";
        }
        try {
            var tree = objectMapper.readTree(body);
            sanitizeNode(tree);
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            return OMITTED;
        }
    }

    /**
     * Contextual names from {@link MaskingPolicy#contextualMaskedFields} are always masked entirely. Their
     * sensitivity depends on sibling fields, and partial disclosure is not configurable here. This is an
     * extension point with no built-in contextual policies.
     */
    private MaskStrategy strategyFor(String field, Set<String> contextualMaskedFields) {
        var strategy = policy.strategyFor(field);
        if (strategy != null) {
            return strategy;
        }
        return contextualMaskedFields.contains(field.toLowerCase(Locale.ROOT)) ? MaskStrategy.FULL : null;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    private void sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            var siblingFields = objectNode.propertyStream()
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            var contextualMaskedFields = policy.contextualMaskedFields(siblingFields);
            for (var field : List.copyOf(objectNode.properties())) {
                var strategy = strategyFor(field.getKey(), contextualMaskedFields);
                if (strategy != null) {
                    objectNode.set(field.getKey(),
                            TextNode.valueOf(MaskingFunctions.apply(strategy, field.getValue().asText())));
                } else {
                    sanitizeNode(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (var i = 0; i < arrayNode.size(); i++) {
                sanitizeNode(arrayNode.get(i));
            }
        }
    }
}

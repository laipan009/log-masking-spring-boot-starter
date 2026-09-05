package io.github.laipan009.logmasking.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Indexes JSON field names annotated with {@link Mask} and {@link MaskScope#ALL}.
 * <p>The encoder receives text, not objects. This index contributes a {@link MaskingPolicy} so {@link
 * LineMasker} can mask annotated names alongside defaults.
 * <p>Matching is by name, not type: annotating {@code comment} in one DTO masks that name in any log
 * line. Use {@code @Mask(scope = TYPED)} for member-specific behavior.
 */
public final class MaskFieldIndex implements MaskingPolicy {

    public static final MaskFieldIndex EMPTY = new MaskFieldIndex(Set.of(), 0);

    private final Set<String> fieldNames;
    private final Map<String, MaskStrategy> strategies;
    private final int scannedClasses;

    public MaskFieldIndex(Collection<String> fieldNames, int scannedClasses) {
        this(fieldNames, Map.of(), scannedClasses);
    }

    public MaskFieldIndex(Collection<String> fieldNames,
                          Map<String, MaskStrategy> strategies,
                          int scannedClasses) {
        this.fieldNames = Set.copyOf(new LinkedHashSet<>(fieldNames));
        this.strategies = Map.copyOf(new LinkedHashMap<>(strategies));
        this.scannedClasses = scannedClasses;
    }

    @Override
    public Set<String> maskedFields() {
        return fieldNames;
    }

    @Override
    public Map<String, MaskStrategy> fieldStrategies() {
        return strategies;
    }

    public Set<String> fieldNames() {
        return fieldNames;
    }

    public int scannedClasses() {
        return scannedClasses;
    }

    public boolean isEmpty() {
        return fieldNames.isEmpty();
    }

    /**
     * Formats a startup summary with stable name ordering and an "and N more" suffix.
     */
    public String describe(int limit) {
        var sorted = fieldNames.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        var shown = sorted.subList(0, Math.min(limit, sorted.size()));
        var tail = sorted.size() - shown.size();
        return tail > 0 ? shown + " and " + tail + " more" : shown.toString();
    }

    @Override
    public String toString() {
        return "MaskFieldIndex[fields=" + fieldNames.size() + ", classes=" + scannedClasses + "]";
    }
}

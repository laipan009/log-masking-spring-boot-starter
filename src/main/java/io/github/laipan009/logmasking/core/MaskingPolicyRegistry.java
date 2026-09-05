package io.github.laipan009.logmasking.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Combines policy field names and explicit strategies into one name-to-strategy lookup.
 * <p>Secret headers always receive full masking and cannot be changed to a partial strategy.
 */
public final class MaskingPolicyRegistry {

    private final Map<String, MaskStrategy> fieldStrategies;
    private final Set<String> secretHeaders;
    private final List<MaskingPolicy> policies;
    private final NameTable scanTable;
    private final NameTable headerTable;

    public MaskingPolicyRegistry(Collection<? extends MaskingPolicy> policies) {
        this.policies = List.copyOf(policies);
        fieldStrategies = mergeStrategies(this.policies);
        secretHeaders = normalize(this.policies.stream().flatMap(policy -> policy.secretHeaders().stream()).toList());
        var forScan = new HashMap<>(fieldStrategies);
        secretHeaders.forEach(header -> forScan.put(header, MaskStrategy.FULL));
        scanTable = new NameTable(forScan);
        headerTable = new NameTable(secretHeaders.stream()
                .collect(Collectors.toMap(header -> header, header -> MaskStrategy.FULL)));
    }

    public boolean isMaskedField(String name) {
        return strategyFor(name) != null;
    }

    /**
     * @return the strategy for the field name, or {@code null} when masking is not required
     */
    public MaskStrategy strategyFor(String name) {
        return name == null ? null : fieldStrategies.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Looks up a name directly in the log substring {@code [start, end)}. Secret headers share the lookup
     * table with {@link MaskStrategy#FULL}, overriding field strategies.
     * <p>This avoids allocating substrings and lowercase copies for every candidate key in a log line.
     */
    public MaskStrategy strategyForLine(String line, int start, int end) {
        return scanTable.lookup(line, start, end);
    }

    public Set<String> maskedFields() {
        return fieldStrategies.keySet();
    }

    public Set<String> contextualMaskedFields(Set<String> siblingFields) {
        var siblings = caseInsensitive(siblingFields);
        return normalize(policies.stream()
                .flatMap(policy -> policy.contextualMaskedFields(siblings).stream())
                .toList());
    }

    /**
     * Checks whether a log substring names a secret header. Separate from {@link #strategyForLine} because
     * bare colon-separated pairs are recognized only for headers; ordinary labels can introduce unbounded
     * prose.
     */
    public boolean isSecretHeader(String line, int start, int end) {
        return headerTable.lookup(line, start, end) != null;
    }

    public boolean isSecretHeader(String name) {
        return name != null && secretHeaders.contains(name.toLowerCase(Locale.ROOT));
    }


    /**
     * Merges policies in two stages: plain field names establish implicit full masking, then explicit
     * strategies override those defaults. This allows configured {@code LAST4} or {@code HASH} for a
     * default field.
     * <p>Conflicts between explicit strategies resolve to the more restrictive option, independently of
     * Spring bean order.
     */
    private static Map<String, MaskStrategy> mergeStrategies(List<MaskingPolicy> policies) {
        var merged = new HashMap<String, MaskStrategy>();
        for (var policy : policies) {
            for (var field : policy.maskedFields()) {
                put(merged, field, MaskStrategy.FULL);
            }
        }
        var explicit = new HashMap<String, MaskStrategy>();
        for (var policy : policies) {
            for (var entry : policy.fieldStrategies().entrySet()) {
                mergeStrongest(explicit, entry.getKey(), entry.getValue());
            }
        }
        merged.putAll(explicit);
        return Map.copyOf(merged);
    }

    private static void mergeStrongest(Map<String, MaskStrategy> target, String field, MaskStrategy strategy) {
        if (field == null || field.isBlank() || strategy == null) {
            return;
        }
        target.merge(field.toLowerCase(Locale.ROOT), strategy, MaskStrategy::strongest);
    }

    private static void put(Map<String, MaskStrategy> target, String field, MaskStrategy strategy) {
        if (field == null || field.isBlank() || strategy == null) {
            return;
        }
        target.put(field.toLowerCase(Locale.ROOT), strategy);
    }

    /**
     * Provides original sibling spellings in a case-insensitive set so handwritten contextual rules work
     * independently of the JSON field casing.
     */
    private static Set<String> caseInsensitive(Collection<String> values) {
        var siblings = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        siblings.addAll(values);
        return Collections.unmodifiableSet(siblings);
    }

    private Set<String> normalize(Collection<String> values) {
        var normalized = new LinkedHashSet<String>();
        values.forEach(value -> normalized.add(value.toLowerCase(Locale.ROOT)));
        return Set.copyOf(normalized);
    }

    /**
     * Allocation-free substring lookup table. Stored keys are lowercase; incoming characters use the same
     * {@link #lower(char)} function for hashing and equality.
     * <p>Uses open addressing, linear probing and a maximum half-full table. Names are fixed at startup.
     */
    private static final class NameTable {

        private final String[] keys;
        private final MaskStrategy[] values;
        private final int mask;
        private final int maxLength;

        NameTable(Map<String, MaskStrategy> entries) {
            var capacity = Integer.highestOneBit(Math.max(4, entries.size() * 2 - 1)) * 2;
            keys = new String[capacity];
            values = new MaskStrategy[capacity];
            mask = capacity - 1;
            var longest = 0;
            for (var entry : entries.entrySet()) {
                var key = entry.getKey();
                longest = Math.max(longest, key.length());
                var slot = hash(key, 0, key.length()) & mask;
                while (keys[slot] != null) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = key;
                values[slot] = entry.getValue();
            }
            maxLength = longest;
        }

        MaskStrategy lookup(String line, int start, int end) {
            var length = end - start;
            if (length <= 0 || length > maxLength) {
                return null;
            }
            for (var slot = hash(line, start, end) & mask; ; slot = (slot + 1) & mask) {
                var key = keys[slot];
                if (key == null) {
                    return null;
                }
                if (key.length() == length && matches(key, line, start)) {
                    return values[slot];
                }
            }
        }

        private static boolean matches(String key, String line, int start) {
            for (var i = 0; i < key.length(); i++) {
                if (key.charAt(i) != lower(line.charAt(start + i))) {
                    return false;
                }
            }
            return true;
        }

        private static int hash(String value, int start, int end) {
            var h = 0;
            for (var i = start; i < end; i++) {
                h = 31 * h + lower(value.charAt(i));
            }
            return (h ^ (h >>> 16)) & Integer.MAX_VALUE;
        }

        /**
         * Fast path for Latin field names; other characters use {@link Character#toLowerCase(char)}.
         */
        private static char lower(char c) {
            if (c >= 'A' && c <= 'Z') {
                return (char) (c + 32);
            }
            return c < 128 ? c : Character.toLowerCase(c);
        }
    }
}

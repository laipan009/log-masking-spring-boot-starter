package io.github.laipan009.logmasking.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class MaskingFunctions {

    private static final int VISIBLE_TAIL = 4;

    /**
     * Hash length in hexadecimal characters. A 32-bit prefix supports approximate correlation but can
     * collide.
     */
    private static final int HASH_LENGTH = 8;

    private MaskingFunctions() {
    }

    public static String apply(MaskStrategy strategy, String value) {
        if (value == null || value.isEmpty()) {
            return LogMasker.MASK;
        }
        return switch (strategy) {
            case FULL -> LogMasker.MASK;
            case LAST4 -> last4(value);
            case HASH -> hash(value);
        };
    }

    /**
     * Checks whether {@code value[start, end)} already has the form the strategy would produce: applying
     * it again would change nothing (or, for {@link MaskStrategy#HASH}, would corrupt the hash).
     */
    static boolean isMasked(MaskStrategy strategy, String value, int start, int end) {
        return switch (strategy) {
            case FULL -> isMask(value, start, end);
            case LAST4 -> end - start >= LogMasker.MASK.length() && value.startsWith(LogMasker.MASK, start)
                    && value.codePointCount(start + LogMasker.MASK.length(), end) <= VISIBLE_TAIL;
            case HASH -> isHash(value, start, end);
        };
    }

    private static boolean isMask(String value, int start, int end) {
        return end - start == LogMasker.MASK.length() && value.startsWith(LogMasker.MASK, start);
    }

    /**
     * Checks whether {@code value[start, end)} already has the {@link MaskStrategy#HASH} form: a hash sign
     * followed by {@value #HASH_LENGTH} lowercase hexadecimal characters.
     */
    static boolean isHash(String value, int start, int end) {
        if (end - start != HASH_LENGTH + 1 || value.charAt(start) != '#') {
            return false;
        }
        for (var i = start + 1; i < end; i++) {
            var c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Masks values of {@value #VISIBLE_TAIL} characters or fewer entirely. Counts Unicode code points
     * rather than {@code char} units to avoid splitting a surrogate pair.
     */
    private static String last4(String value) {
        var length = value.length();
        if (value.codePointCount(0, length) <= VISIBLE_TAIL) {
            return LogMasker.MASK;
        }
        return LogMasker.MASK + value.substring(value.offsetByCodePoints(length, -VISIBLE_TAIL));
    }

    /**
     * Prefixes the result with a hash sign to distinguish it from a truncated value.
     * <p>This is not cryptographic protection: short values with known formats can be guessed by
     * enumeration. Its purpose is approximate correlation without printing the original value.
     */
    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "#" + HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JVM; if unavailable, mask entirely instead of failing.
            return LogMasker.MASK;
        }
    }
}

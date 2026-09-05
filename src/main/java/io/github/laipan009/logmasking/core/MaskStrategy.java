package io.github.laipan009.logmasking.core;

/**
 * Defines how a field value is masked. The default is {@link #FULL}.
 * <p>Configuring partial disclosure such as {@link #LAST4} explicitly allows part of the original
 * value to appear in logs.
 */
public enum MaskStrategy {

    /**
     * Keeps the last four characters, for example {@code ***8900}. Values shorter than five characters are
     * masked entirely.
     */
    LAST4,

    /**
     * Produces a stable hash such as {@code #a1b2c3d4}. Equal values produce equal hashes for log
     * correlation without printing the original value.
     */
    HASH,

    /**
     * Replaces the entire value with {@code ***}. The default strategy.
     */
    FULL;

    /**
     * Ranks strategies by disclosure. When policies assign different explicit strategies to one name, the
     * more restrictive strategy wins, independently of bean registration order.
     */
    int strength() {
        return ordinal();
    }

    static MaskStrategy strongest(MaskStrategy left, MaskStrategy right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.strength() >= right.strength() ? left : right;
    }
}

package io.github.laipan009.logmasking.core;

/**
 * A named log field such as {@code fio=***}. Pass as an SLF4J placeholder argument: masking is
 * deferred to {@link #toString()} when the message is actually formatted, and the masking encoder
 * recognizes the argument and keeps its rendered pair instead of masking it a second time. Concatenated
 * into the message string, it is masked again like any other text.
 */
public final class MaskedField implements CharSequence {

    private final String name;
    private final Object value;
    private final MaskStrategy strategy;
    private String rendered;

    MaskedField(String name, Object value, MaskStrategy strategy) {
        this.name = name;
        this.value = value;
        this.strategy = strategy;
    }

    public String name() {
        return name;
    }

    MaskStrategy strategy() {
        return strategy;
    }

    /**
     * Returns the masked value; {@code null} remains the text {@code "null"}, as in a record's toString
     * output.
     */
    public String value() {
        return value == null ? "null" : MaskingFunctions.apply(strategy, String.valueOf(value));
    }

    @Override
    public String toString() {
        var result = rendered;
        if (result == null) {
            result = name + "=" + value();
            rendered = result;
        }
        return result;
    }

    @Override
    public int length() {
        return toString().length();
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return toString().subSequence(start, end);
    }
}

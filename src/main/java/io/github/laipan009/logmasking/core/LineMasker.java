package io.github.laipan009.logmasking.core;

/**
 * Masks a rendered Logback line using a single-pass scanner.
 * <p>Unlike {@link PayloadSanitizer}, this operates on mixed text: prefixes, messages, stack traces
 * and embedded payloads. It recognizes quoted JSON keys, {@code key=value} pairs, and colon-separated
 * secret headers. All matching is by field name.
 * <p>Unnamed values cannot be matched. Plain-text value boundaries are approximate: {@code note=Hello,
 * John} ends at the comma if {@code note} is configured. The original value type and declaring class
 * are unavailable.
 */
public final class LineMasker {

    /**
     * Limits the backward key scan before {@code =}; longer names are not supported by the line scanner.
     */
    private static final int MAX_KEY_LENGTH = 64;

    private final MaskingPolicyRegistry policy;

    public LineMasker(MaskingPolicyRegistry policy) {
        this.policy = policy;
    }

    public String mask(String line) {
        return mask(line, null);
    }

    /**
     * Masks a line rendered from a logging call with these arguments.
     * <p>{@link MaskedField} arguments are already masked by {@link LogMasker}. Where the line contains
     * such a field exactly as rendered, the pair is kept verbatim. Masking it again would apply the global
     * strategy on top of the member's own (hashing a hash, overriding {@code @Mask}), and the plain-text
     * boundary would swallow the message text that follows the placeholder.
     * @param arguments arguments of the logging call, or {@code null}
     */
    public String mask(String line, Object[] arguments) {
        return line == null || line.isEmpty() ? line : maskNamedValues(line, arguments);
    }

    /**
     * Single-pass scanner. Allocates a buffer only when a match is found; otherwise returns the original
     * string instance.
     * <p>Quoted fragments that are not recognized keys are still scanned: structured encoders put the
     * entire message inside a string. {@code stringEnd} prevents replacements from consuming its closing
     * quote. An unmatched quote is treated as an ordinary character so scanning can continue.
     */
    private String maskNamedValues(String line, Object[] arguments) {
        StringBuilder out = null;
        var copied = 0;
        var length = line.length();
        var stringEnd = -1;
        var i = 0;
        while (i < length) {
            if (i >= stringEnd) {
                stringEnd = -1;
            }
            var c = line.charAt(i);
            if (c == '"') {
                var keyEnd = endOfQuoted(line, i);
                if (keyEnd >= 0) {
                    var colon = skipSpaces(line, keyEnd + 1);
                    MaskStrategy strategy;
                    if (colon < length && line.charAt(colon) == ':'
                            && (strategy = policy.strategyForLine(line, i + 1, keyEnd)) != null) {
                        var valueStart = skipSpaces(line, colon + 1);
                        var valueEnd = endOfJsonValue(line, valueStart);
                        if (valueEnd > valueStart) {
                            if (!isHashed(strategy, line, valueStart, valueEnd)) {
                                out = copyUpTo(out, line, copied, valueStart);
                                out.append('"').append(replacement(strategy, line, valueStart, valueEnd)).append('"');
                                copied = valueEnd;
                            }
                            i = valueEnd;
                            continue;
                        }
                    }
                    stringEnd = keyEnd;
                }
                i++;
                continue;
            }
            if (c == '=' || c == ':') {
                var keyEnd = endOfIdentifier(line, i);
                var keyStart = startOfIdentifier(line, keyEnd);
                if (keyStart >= 0 && c == '=' && keyEnd == i) {
                    var renderedEnd = endOfRenderedField(line, keyStart, i, arguments,
                            policy.isSecretHeader(line, keyStart, keyEnd));
                    if (renderedEnd >= 0) {
                        i = renderedEnd;
                        continue;
                    }
                }
                MaskStrategy strategy;
                if (keyStart >= 0 && (strategy = strategyFor(c, line, keyStart, keyEnd)) != null) {
                    var valueStart = skipSpaces(line, i + 1);
                    var valueEnd = endOfPlainValue(line, valueStart, stringEnd < 0 ? length : stringEnd);
                    if (valueEnd > valueStart) {
                        if (!isHashed(strategy, line, valueStart, valueEnd)) {
                            out = copyUpTo(out, line, copied, valueStart);
                            out.append(replacement(strategy, line, valueStart, valueEnd));
                            copied = valueEnd;
                        }
                        i = valueEnd;
                        continue;
                    }
                }
            }
            i++;
        }
        if (out == null) {
            return line;
        }
        out.append(line, copied, length);
        return out.toString();
    }

    /**
     * Selects the lookup according to the separator.
     * <p>{@code =} uses all configured names for generated toString, record and query values. A bare
     * {@code :} matches only secret headers such as {@code Authorization: Bearer ...}. Ordinary labels
     * often introduce prose with no reliable boundary, so masking them could consume the rest of the
     * message.
     */
    private MaskStrategy strategyFor(char separator, String line, int keyStart, int keyEnd) {
        if (separator == ':') {
            return policy.isSecretHeader(line, keyStart, keyEnd) ? MaskStrategy.FULL : null;
        }
        return policy.strategyForLine(line, keyStart, keyEnd);
    }

    /**
     * Returns the end of a {@link MaskedField} argument rendered at {@code keyStart}, or -1.
     * <p>The rendered pair holds only the name and the already masked value, so keeping it verbatim
     * discloses nothing new. A letter or digit right after it means the text only starts like the field,
     * for example a longer raw value, and is masked as usual.
     * <p>Secret headers are always fully masked, so a header field is kept only when it is fully masked
     * too; a member {@code @Mask} with a partial strategy does not weaken a header.
     */
    private static int endOfRenderedField(String line, int keyStart, int separator, Object[] arguments,
                                          boolean secretHeader) {
        if (arguments == null) {
            return -1;
        }
        for (var argument : arguments) {
            if (argument instanceof MaskedField field && field.name().length() == separator - keyStart
                    && (!secretHeader || field.strategy() == MaskStrategy.FULL)) {
                var rendered = field.toString();
                var end = keyStart + rendered.length();
                if (line.startsWith(rendered, keyStart)
                        && (end == line.length() || !Character.isLetterOrDigit(line.charAt(end)))) {
                    return end;
                }
            }
        }
        return -1;
    }

    /**
     * Leaves a value that already has the {@link MaskStrategy#HASH} form unchanged. {@link LogMasker}
     * output such as a {@code maskJson} body reaches the encoder already hashed; hashing it again would
     * break correlation with values hashed once. As a consequence, a raw value of exactly this form in a
     * HASH field is not hashed.
     */
    private static boolean isHashed(MaskStrategy strategy, String line, int start, int end) {
        if (strategy != MaskStrategy.HASH) {
            return false;
        }
        if (end - start >= 2) {
            var first = line.charAt(start);
            if ((first == '"' || first == '\'') && line.charAt(end - 1) == first) {
                return MaskingFunctions.isHash(line, start + 1, end - 1);
            }
        }
        return MaskingFunctions.isHash(line, start, end);
    }

    /**
     * The common {@link MaskStrategy#FULL} path inserts a constant without reading the value. Other
     * strategies require a substring and removal of surrounding quotes.
     */
    private static String replacement(MaskStrategy strategy, String line, int valueStart, int valueEnd) {
        if (strategy == MaskStrategy.FULL) {
            return LogMasker.MASK;
        }
        return MaskingFunctions.apply(strategy, unquote(line, valueStart, valueEnd));
    }

    /**
     * Removes surrounding quotes so single- and double-quoted values produce identical strategy results;
     * otherwise {@code LAST4} could expose a quote instead of a digit.
     */
    private static String unquote(String line, int start, int end) {
        if (end - start >= 2) {
            var first = line.charAt(start);
            if ((first == '"' || first == '\'') && line.charAt(end - 1) == first) {
                return line.substring(start + 1, end - 1);
            }
        }
        return line.substring(start, end);
    }


    private static StringBuilder copyUpTo(StringBuilder out, String line, int copied, int upTo) {
        var builder = out == null ? new StringBuilder(line.length() + 16) : out;
        builder.append(line, copied, upTo);
        return builder;
    }

    /**
     * Returns the closing quote index, accounting for escaping, or -1.
     */
    private static int endOfQuoted(String line, int openingQuote) {
        return endOfQuoted(line, openingQuote, '"');
    }

    private static int endOfQuoted(String line, int openingQuote, char quote) {
        for (var i = openingQuote + 1; i < line.length(); i++) {
            var c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    private static int skipSpaces(String line, int from) {
        var i = from;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /**
     * Finds the end of a JSON value. Returns {@code start} for the {@code null} literal; see {@link
     * #isNullLiteral}.
     */
    private static int endOfJsonValue(String line, int start) {
        if (start >= line.length()) {
            return start;
        }
        var c = line.charAt(start);
        if (c == '"') {
            var end = endOfQuoted(line, start);
            return end < 0 ? line.length() : end + 1;
        }
        if (c == '{' || c == '[') {
            return endOfBalanced(line, start);
        }
        if (isNullLiteral(line, start, true)) {
            return start;
        }
        var i = start;
        while (i < line.length() && !isJsonValueEnd(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Preserves {@code null}, which indicates an absent value. Boolean values are masked like other
     * values: a configured sensitive flag can itself disclose personal information.
     */
    private static boolean isNullLiteral(String line, int start, boolean json) {
        if (!line.startsWith("null", start)) {
            return false;
        }
        var after = start + 4;
        if (after == line.length()) {
            return true;
        }
        var next = line.charAt(after);
        return json ? isJsonValueEnd(next) : isPlainValueEnd(next);
    }

    private static boolean isJsonValueEnd(char c) {
        return c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r';
    }

    /**
     * Finds a plain-text value boundary, traversing nested brackets as one value. A configured {@code
     * passport=Passport(series=12, number=34)} becomes one mask.
     * <p>{@code limit} is the enclosing quoted fragment boundary; it prevents consuming the closing quote
     * and the rest of a structured log entry.
     */
    private static int endOfPlainValue(String line, int start, int limit) {
        if (start >= limit) {
            return start;
        }
        var c = line.charAt(start);
        if (c == '"' || c == '\'') {
            var end = endOfQuoted(line, start, c);
            return end < 0 ? limit : Math.min(end + 1, limit);
        }
        if (c == '(' || c == '[' || c == '{') {
            return Math.min(endOfBalanced(line, start), limit);
        }
        if (isNullLiteral(line, start, false)) {
            return start;
        }
        var i = start;
        while (i < limit && !isPlainValueEnd(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Spaces remain part of a value, as in {@code fullName=John Smith}. An ampersand ends a query value so
     * masking preserves subsequent parameters and diagnostic context.
     */
    private static boolean isPlainValueEnd(char c) {
        return c == ',' || c == ')' || c == ']' || c == '}' || c == '&' || c == '\n' || c == '\r';
    }

    /**
     * Returns the position after the matching bracket, traversing quoted strings as units.
     */
    private static int endOfBalanced(String line, int start) {
        var depth = 0;
        for (var i = start; i < line.length(); i++) {
            var c = line.charAt(i);
            if (c == '"') {
                var end = endOfQuoted(line, i);
                if (end < 0) {
                    return line.length();
                }
                i = end;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return line.length();
    }

    /**
     * Finds the identifier ending at {@code end}, or -1. A dot is a separator: {@code user.email=x}
     * matches {@code email}.
     */
    private static int startOfIdentifier(String line, int end) {
        var i = end;
        while (i > 0 && isIdentifierPart(line.charAt(i - 1))) {
            i--;
        }
        return i == end || end - i > MAX_KEY_LENGTH ? -1 : i;
    }

    /**
     * Returns the position after the name, allowing spaces before {@code =}.
     */
    private static int endOfIdentifier(String line, int equalsIndex) {
        var i = equalsIndex;
        while (i > 0 && (line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
            i--;
        }
        return i;
    }

    /**
     * Hyphens belong to names so headers such as {@code X-Auth-Token} are matched as a whole.
     */
    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-';
    }
}

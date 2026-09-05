package io.github.laipan009.logmasking.core;

import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit masking for values that have no name in a log message.
 * <p>The encoder can match named pairs but cannot identify a bare getter value passed to a logging
 * placeholder. {@link #field} supplies a name and applies masking rules. Whole objects can be logged
 * normally when their {@code toString()} includes named fields.
 */
public final class LogMasker {

    public static final String MASK = "***";

    private static final Logger LOG = LoggerFactory.getLogger(LogMasker.class);

    private static final int WARNED_NAMES_LIMIT = 1000;

    private final PayloadSanitizer sanitizer;
    private final MaskingPolicyRegistry policy;
    private final MethodReferenceResolver resolver;
    private final Set<String> warnedNames = ConcurrentHashMap.newKeySet();

    public LogMasker(PayloadSanitizer sanitizer, MaskingPolicyRegistry policy) {
        this(sanitizer, policy, (PropertyNamingStrategy) null);
    }

    /**
     * Uses the global {@code ObjectMapper} naming strategy to match JSON field names.
     */
    public LogMasker(PayloadSanitizer sanitizer, MaskingPolicyRegistry policy, PropertyNamingStrategy naming) {
        this(sanitizer, policy, new MethodReferenceResolver(policy, naming, LogMasker::warnUnlisted));
    }

    LogMasker(PayloadSanitizer sanitizer, MaskingPolicyRegistry policy, MethodReferenceResolver resolver) {
        this.sanitizer = sanitizer;
        this.policy = policy;
        this.resolver = resolver;
    }

    /**
     * Formats {@code field(dto, Dto::getFio)} as {@code fio=***}. Uses the member's {@code @Mask}
     * strategy, including {@code TYPED}, or the global policy. Unknown names are fully masked with one
     * warning per call site.
     * @throws IllegalArgumentException for a lambda body or bound reference instead of an unbound getter
     * reference
     */
    public <T> MaskedField field(T bean, SerializableFunction<T, ?> getter) {
        if (getter == null) {
            throw new IllegalArgumentException("field(): expected a method reference (Dto::getFio)");
        }
        var member = resolver.resolve(getter);
        return new MaskedField(member.name(), bean == null ? null : getter.apply(bean), member.strategy());
    }

    public MaskedField field(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field(): field name is required");
        }
        var strategy = policy.strategyFor(name);
        if (strategy == null) {
            strategy = MaskStrategy.FULL;
            if (!policy.isSecretHeader(name)) {
                warnUnlistedOnce(name);
            }
        }
        return new MaskedField(name, value, strategy);
    }

    public String maskJson(String body) {
        return sanitizer.sanitizeJson(body);
    }

    public int maxBodyBytes() {
        return sanitizer.maxBodyBytes();
    }

    private void warnUnlistedOnce(String name) {
        var key = name.toLowerCase(Locale.ROOT);
        if (warnedNames.size() < WARNED_NAMES_LIMIT && warnedNames.add(key)) {
            warnUnlisted(name);
        }
    }

    private static void warnUnlisted(String name) {
        LOG.warn("Field {} is not in the masking policy; masked entirely. "
                + "Add @Mask or logging.masking.additional-masked-fields", name);
    }
}

package io.github.laipan009.logmasking.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for log masking.
 * <p>The default {@link MaskScope#ALL} adds its name and strategy to {@link MaskFieldIndex}, covering
 * the same name in any log line. {@link MaskScope#TYPED} limits masking to {@code LogMasker.field(dto,
 * Dto::getFio)} calls.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Mask {

    MaskScope scope() default MaskScope.ALL;

    /**
     * Selects the replacement strategy. With {@code scope = ALL}, the strategy is also indexed for line
     * masking.
     */
    MaskStrategy strategy() default MaskStrategy.FULL;
}

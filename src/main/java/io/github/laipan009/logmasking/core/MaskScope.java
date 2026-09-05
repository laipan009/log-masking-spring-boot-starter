package io.github.laipan009.logmasking.core;

public enum MaskScope {

    /**
     * Adds the field name to {@link MaskFieldIndex}, making it available to {@link LineMasker} for every
     * log line.
     */
    ALL,

    /**
     * Masks only through {@code LogMasker.field(dto, Dto::getFio)}, where the member annotation is
     * available. Excludes the name from the index, leaving identically named fields elsewhere unaffected.
     */
    TYPED
}

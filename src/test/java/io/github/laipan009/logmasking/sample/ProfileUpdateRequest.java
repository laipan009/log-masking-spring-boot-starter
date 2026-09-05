package io.github.laipan009.logmasking.sample;

import io.github.laipan009.logmasking.core.Mask;
import io.github.laipan009.logmasking.core.MaskScope;

public record ProfileUpdateRequest(
        String taskId,
        @Mask String comment,
        /**
         * Not a default name: covered only through the annotation index.
         */
        @Mask String requesterAlias,
        @Mask String contactEmail,
        @Mask(scope = MaskScope.TYPED) String internalNote) {
}

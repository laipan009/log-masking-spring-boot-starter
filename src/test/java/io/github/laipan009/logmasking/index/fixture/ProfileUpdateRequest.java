package io.github.laipan009.logmasking.index.fixture;

import io.github.laipan009.logmasking.core.Mask;
import io.github.laipan009.logmasking.core.MaskScope;

public record ProfileUpdateRequest(
        String taskId,
        @Mask String comment,
        @Mask String email,
        @Mask(scope = MaskScope.TYPED) String note) {
}

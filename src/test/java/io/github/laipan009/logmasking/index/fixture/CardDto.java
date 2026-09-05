package io.github.laipan009.logmasking.index.fixture;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.laipan009.logmasking.core.Mask;
import io.github.laipan009.logmasking.core.MaskStrategy;

public record CardDto(
        @Mask(strategy = MaskStrategy.LAST4) String cardNumber,

        @Mask(strategy = MaskStrategy.HASH)
        @JsonProperty("passport_series")
        String passportSeries) {
}

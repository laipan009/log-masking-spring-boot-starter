package io.github.laipan009.logmasking.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaskingFunctions: value replacement")
class MaskingFunctionsTest {

    @ParameterizedTest(name = "LAST4({0}) = {1}")
    @CsvSource({
            "12345678900, ***8900",
            "12345, ***2345",
            "1234, ***",
            "123, ***",
            "'', ***"})
    @DisplayName("Short values are fully masked instead of exposing all four characters")
    void apply_whenLast4Strategy_shouldRevealTailOnlyWhenValueLongerThanFour(String value, String expected) {
        assertThat(MaskingFunctions.apply(MaskStrategy.LAST4, value)).isEqualTo(expected);
    }

    @Test
    void apply_whenValueIsNull_shouldReturnFullMask() {
        assertThat(MaskingFunctions.apply(MaskStrategy.LAST4, null)).isEqualTo("***");
        assertThat(MaskingFunctions.apply(MaskStrategy.HASH, null)).isEqualTo("***");
    }

    @Test
    @DisplayName("The hash does not contain the original value")
    void apply_whenHashStrategy_shouldNotContainSourceValue() {
        assertThat(MaskingFunctions.apply(MaskStrategy.HASH, "12345678900")).doesNotContain("1234");
    }

    @ParameterizedTest(name = "HASH({0}) = {1}")
    @CsvSource({
            "12345678900, #a8476735",
            "\u0418\u0432\u0430\u043d\u043e\u0432 \u0418\u0432\u0430\u043d, #76c8f6d9"})
    @DisplayName("Pins the SHA-256 output to preserve correlation "
            + "with earlier log entries")
    void apply_whenHashStrategy_shouldReturnStableSha256Digest(String value, String expected) {
        assertThat(MaskingFunctions.apply(MaskStrategy.HASH, value)).isEqualTo(expected);
    }

    @Test
    @DisplayName("LAST4 counts code points without splitting surrogate pairs")
    void apply_whenValueContainsSurrogatePairs_shouldKeepFourWholeCodePoints() {
        var masked = MaskingFunctions.apply(MaskStrategy.LAST4, "contract \uD83D\uDCC4\uD83D\uDD12\uD83D\uDCC8\uD83D\uDD11");

        assertThat(masked).isEqualTo("***\uD83D\uDCC4\uD83D\uDD12\uD83D\uDCC8\uD83D\uDD11");
        var tail = masked.substring(LogMasker.MASK.length());
        assertThat(tail.codePointCount(0, tail.length()))
                .as("Keeps exactly four code points, not four char units")
                .isEqualTo(4);
    }

}

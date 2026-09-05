package io.github.laipan009.logmasking.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.autoconfigure.MaskFieldIndexScanner;
import io.github.laipan009.logmasking.core.MaskFieldIndex;
import io.github.laipan009.logmasking.core.MaskStrategy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaskFieldIndexScanner")
class MaskFieldIndexScannerTest {

    private static final String FIXTURES = "io.github.laipan009.logmasking.index.fixture";

    @Nested
    @DisplayName("Collects @Mask field names")
    class CollectedNames {

        private final MaskFieldIndex index = scan(new ObjectMapper());

        @Test
        void whenRecordComponentsAnnotated_shouldCollectTheirNames() {
            assertThat(index.fieldNames()).contains("comment", "email");
        }

        @Test
        void whenClassAndNestedClassFieldsAnnotated_shouldCollectTheirNames() {
            assertThat(index.fieldNames()).contains("fullName", "homePhone");
        }

        @Test
        void whenGetterIsAnnotated_shouldCollectPropertyName() {
            assertThat(index.fieldNames()).contains("passportNumber");
        }

        @Test
        @DisplayName("@JsonProperty overrides the field name to match JSON")
        void whenJsonPropertyOverridesFieldName_shouldCollectBothSpellings() {
            assertThat(index.fieldNames())
                    .as("JSON uses passport_number while toString uses passportNumber")
                    .contains("passport_number", "passportNumber");
        }

        @Test
        @DisplayName("Class-level @JsonNaming contributes both spellings")
        void whenClassLevelJsonNamingDeclared_shouldCollectBothSpellings() {
            assertThat(index.fieldNames()).contains("birthDate", "birth_date");
        }

        @Test
        @DisplayName("TYPED scope excludes the field from the name index")
        void whenScopeIsTyped_shouldNotCollectFieldName() {
            assertThat(index.fieldNames()).doesNotContain("note");
        }

        @Test
        void whenFieldHasNoMaskAnnotation_shouldNotCollectFieldName() {
            assertThat(index.fieldNames()).doesNotContain("taskId", "position");
        }
    }

    @Nested
    @DisplayName("Respects global ObjectMapper naming")
    class GlobalNamingStrategy {

        @Test
        void whenMapperUsesSnakeCaseStrategy_shouldCollectBothSpellings() {
            var objectMapper = new ObjectMapper()
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

            var index = scan(objectMapper);

            assertThat(index.fieldNames()).contains("fullName", "full_name");
        }
    }

    @Nested
    @DisplayName("Strategies from @Mask")
    class Strategies {

        @Test
        @DisplayName("ALL scope contributes both names and explicit strategies")
        void whenScopeIsAllWithStrategy_shouldCollectNameAndStrategy() {
            var index = scan(new ObjectMapper());

            assertThat(index.fieldNames()).contains("cardNumber");
            assertThat(index.fieldStrategies()).containsEntry("cardNumber", MaskStrategy.LAST4);
        }

        @Test
        @DisplayName("FULL is implicit and needs no explicit strategy entry")
        void whenStrategyIsDefaultFull_shouldNotStoreStrategy() {
            assertThat(scan(new ObjectMapper()).fieldStrategies()).doesNotContainKey("fullName");
        }

        @Test
        @DisplayName("Propagates the strategy to every field name spelling")
        void whenNameHasSeveralSpellings_shouldApplyStrategyToEachSpelling() {
            var strategies = scan(new ObjectMapper()).fieldStrategies();

            assertThat(strategies)
                    .containsEntry("passportSeries", MaskStrategy.HASH)
                    .containsEntry("passport_series", MaskStrategy.HASH);
        }
    }

    @Nested
    @DisplayName("Empty scan package list")
    class NoPackages {

        @Test
        void whenPackageListIsEmpty_shouldReturnEmptyIndexWithoutScannedClasses() {
            var index = new MaskFieldIndexScanner(new ObjectMapper(), getClass().getClassLoader()).scan(List.of());

            assertThat(index.isEmpty()).isTrue();
            assertThat(index.scannedClasses()).isZero();
        }
    }

    @Nested
    @DisplayName("Contributes a MaskingPolicy")
    class AsPolicy {

        @Test
        void whenLimitIsBelowNameCount_shouldDescribeLimitedNamesWithRemainder() {
            var index = new MaskFieldIndex(List.of("a", "b", "c"), 3);

            assertThat(index.describe(2)).isEqualTo("[a, b] and 1 more");
        }
    }

    private MaskFieldIndex scan(ObjectMapper objectMapper) {
        return new MaskFieldIndexScanner(objectMapper, getClass().getClassLoader()).scan(List.of(FIXTURES));
    }
}

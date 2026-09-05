package io.github.laipan009.logmasking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.MaskingPolicy;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;
import io.github.laipan009.logmasking.core.PayloadSanitizer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadSanitizerTest {

    private final PayloadSanitizer sanitizer = new PayloadSanitizer(new ObjectMapper(),
                                                                    new MaskingPolicyRegistry(List.of(
                                                                            new DefaultMaskingPolicy(),
                                                                            new BirthPlaceContextPolicy(),
                                                                            new AdditionalPolicy())));

    @Nested
    class SanitizeJson {

        @Test
        void whenServicePolicyAddsField_shouldMaskIt() {
            // given
            var body = "{\"customPersonalValue\":\"private\",\"safe\":\"visible\"}";

            // when
            var result = sanitizer.sanitizeJson(body);

            // then
            assertThat(result).isEqualTo("{\"customPersonalValue\":\"***\",\"safe\":\"visible\"}");
        }

        @Test
        void whenJsonIsMalformed_shouldFailClosed() {
            // given
            var body = "{\"fullName\":\"Smith\"";

            // when
            var result = sanitizer.sanitizeJson(body);

            // then
            assertThat(result).isEqualTo("body omitted");
        }

        @Test
        void whenDocumentContainsBirthPlace_shouldMaskContextualGenericFields() {
            // given
            var body = """
                    {"birthdate":"1990-01-01","birthPlace":true,"country":"Example Country",\
                    "region":"Example City","district":"Central","city":"Example City"}
                    """;

            // when
            var result = sanitizer.sanitizeJson(body);

            // then
            assertThat(result).contains("\"country\":\"***\"")
                    .contains("\"region\":\"***\"")
                    .contains("\"district\":\"***\"")
                    .contains("\"city\":\"***\"")
                    .doesNotContain("Example Country")
                    .doesNotContain("Central");
        }

        @Test
        void whenCustomerMainDocumentContainsSpecialBirthPlace_shouldMaskGenericLocationFields() {
            // given
            var body = """
                    {"birthDate":"1990-01-01","placeOfBirth":true,"country":"Example Country",\
                    "region":"Example City","area":"Central","city":"Example City"}
                    """;

            // when
            var result = sanitizer.sanitizeJson(body);

            // then
            assertThat(result).contains("\"country\":\"***\"")
                    .contains("\"region\":\"***\"")
                    .contains("\"area\":\"***\"")
                    .contains("\"city\":\"***\"")
                    .doesNotContain("Example Country")
                    .doesNotContain("Central");
        }

        @Test
        void whenGenericLocationIsNotBirthPlace_shouldKeepDiagnosticFields() {
            // given
            var body = "{\"country\":\"Example Country\",\"region\":\"Example City\",\"city\":\"Example City\"}";

            // when
            var result = sanitizer.sanitizeJson(body);

            // then
            assertThat(result).isEqualTo(body);
        }
    }

    private static class AdditionalPolicy implements MaskingPolicy {

        @Override
        public Set<String> maskedFields() {
            return Set.of("customPersonalValue", "address");
        }
    }

    private static class BirthPlaceContextPolicy implements MaskingPolicy {

        private static final Set<String> BIRTH_PLACE_FIELDS = Set.of("country", "region", "district", "area", "city");

        /**
         * Checks sibling names using the spelling declared in the DTO.
         */
        @Override
        public Set<String> contextualMaskedFields(Set<String> siblingFields) {
            var isBirthDocument = (siblingFields.contains("birthPlace")
                    || siblingFields.contains("placeOfBirth"))
                    && (siblingFields.contains("birthDate") || siblingFields.contains("birthDay"));
            return isBirthDocument ? BIRTH_PLACE_FIELDS : Set.of();
        }
    }
}

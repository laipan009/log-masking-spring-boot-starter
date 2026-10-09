package io.github.laipan009.logmasking.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LineMasker: rendered log line masking")
class LineMaskerTest {

    private static final String PREFIX = "2026-09-01 12:03:44.120 INFO [http-nio-8080-exec-3] "
            + "[a1b2c3,d4e5] com.example.logging.LoggingAspect - ";

    private final LineMasker masker = new LineMasker(new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy())));

    @Test
    @DisplayName("The annotation index extends the global name set")
    void mask_whenFieldIndexAddsName_shouldMaskThatField() {
        var withIndex = new LineMasker(new MaskingPolicyRegistry(
                List.of(new DefaultMaskingPolicy(), new MaskFieldIndex(List.of("accountNumber"), 1))));

        assertThat(withIndex.mask("accountNumber=100500")).isEqualTo("accountNumber=***");
        assertThat(withIndex.mask("{\"accountNumber\":\"100500\"}")).isEqualTo("{\"accountNumber\":\"***\"}");
    }

    @Nested
    @DisplayName("JSON embedded in text")
    class JsonForm {

        @Test
        void whenJsonHasMaskedStringField_shouldMaskOnlyThatValue() {
            var line = PREFIX + "→ body: {\"customerId\":\"E-1\",\"fullName\":\"John Smith\"}";

            var masked = masker.mask(line);

            assertThat(masked).doesNotContain("John Smith").contains("\"fullName\":\"***\"");
            assertThat(masked).as("Preserves fields outside the masking policy").contains("\"customerId\":\"E-1\"");
        }

        @Test
        @DisplayName("Numeric values are masked too")
        void whenJsonValueIsNumber_shouldMaskValue() {
            var line = PREFIX + "{\"passportNumber\":250000,\"grade\":12}";

            assertThat(masker.mask(line)).contains("\"passportNumber\":\"***\"").doesNotContain("250000");
        }

        @Test
        @DisplayName("Masks a nested object entirely")
        void whenJsonValueIsNestedObject_shouldMaskWholeObject() {
            var line = PREFIX + "{\"addressData\":{\"street\":\"Main Street\",\"flat\":\"12\"},\"id\":7}";

            var masked = masker.mask(line);

            assertThat(masked).doesNotContain("Main Street").contains("\"addressData\":\"***\"").contains("\"id\":7");
        }

        @Test
        @DisplayName("null remains unchanged because the value is absent")
        void whenJsonValueIsNull_shouldReturnLineUnchanged() {
            var line = PREFIX + "{\"password\":null,\"status\":\"OK\"}";

            assertThat(masker.mask(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("Boolean values are masked when the field name is sensitive")
        void whenJsonValueIsBoolean_shouldMaskValue() {
            var line = PREFIX + "{\"token\":true,\"deleted\":false}";

            assertThat(masker.mask(line))
                    .contains("\"token\":\"***\"")
                    .contains("\"deleted\":false");
        }

        @Test
        @DisplayName("An escaped quote inside a value preserves its boundaries")
        void whenJsonValueContainsEscapedQuote_shouldMaskOnlyThatValue() {
            var line = PREFIX + "{\"password\":\"he said \\\"yes\\\"\",\"status\":\"OK\"}";

            var masked = masker.mask(line);

            assertThat(masked).contains("\"password\":\"***\"").contains("\"status\":\"OK\"");
        }
    }

    @Nested
    @DisplayName("Plain pairs: Lombok, records, maps and query strings")
    class PlainForm {

        @Test
        @DisplayName("Aspect argument dumps are masked by field name")
        void whenLombokToStringDumpsFields_shouldMaskListedFieldsOnly() {
            var line = PREFIX + "[CustomerController]: Start execution method - update(), "
                    + "with params: {request=CustomerDto(firstName=John, snils=12345678900, attempt=3)}";

            var masked = masker.mask(line);

            assertThat(masked).doesNotContain("John").doesNotContain("12345678900");
            assertThat(masked).contains("firstName=***").contains("snils=***").contains("attempt=3");
        }

        @Test
        void whenRecordToStringDumpsFields_shouldMaskListedFields() {
            var line = PREFIX + "result: CustomerDto[firstName=John, email=ivan@example.com]";

            var masked = masker.mask(line);

            assertThat(masked).contains("firstName=***").contains("email=***").doesNotContain("ivan@example.com");
        }

        @Test
        @DisplayName("Secret headers are masked in third-party logger output")
        void whenLineContainsSecretHeaders_shouldMaskTheirValues() {
            var line = PREFIX + "Headers: [Authorization=Bearer eyJhbGciOi, "
                    + "X-Auth-Token=s-7781-abc, Content-Type=application/json]";

            assertThat(masker.mask(line))
                    .isEqualTo(PREFIX + "Headers: [Authorization=***, "
                            + "X-Auth-Token=***, Content-Type=application/json]");
        }

        @Test
        @DisplayName("Matches only complete explicitly listed names")
        void whenNamesAreDerivedForms_shouldMaskOnlyListedNames() {
            var line = PREFIX + "doc=Data(firstName=Jane, lastName=Smith, "
                    + "phone=17, passwordDate=2026-03-01, longTextData=reference)";

            assertThat(masker.mask(line))
                    .as("Explicitly listed names are masked")
                    .isEqualTo(PREFIX + "doc=Data(firstName=***, lastName=***, "
                            + "phone=***, passwordDate=2026-03-01, longTextData=reference)");
        }

        @Test
        @DisplayName("Query masking stops at & and preserves subsequent parameters")
        void whenValueIsQueryParameter_shouldMaskUntilAmpersandOnly() {
            var line = PREFIX + "GET /api/v1/emp?snils=12345678900&companyId=77 status=200 durationMs=15";

            assertThat(masker.mask(line))
                    .isEqualTo(PREFIX + "GET /api/v1/emp?snils=***&companyId=77 status=200 durationMs=15");
        }

        @Test
        @DisplayName("Single-quoted query value")
        void whenQueryValueIsSingleQuoted_shouldMaskValueWithQuotes() {
            var line = PREFIX + "GET /tech/sap/GetBonus?Snils='12345678900'&$format=json HTTP/1.1";

            assertThat(masker.mask(line))
                    .isEqualTo(PREFIX + "GET /tech/sap/GetBonus?Snils=***&$format=json HTTP/1.1");
        }

        @Test
        @DisplayName("Masks a nested parenthesized value entirely")
        void whenToStringValueIsNestedObject_shouldMaskWholeObject() {
            var line = PREFIX + "addressData=Address(street=Main Street, flat=12), id=7";

            var masked = masker.mask(line);

            assertThat(masked).doesNotContain("Main Street").contains("addressData=***").contains("id=7");
        }

        @Test
        @DisplayName("Allows spaces around the equals sign")
        void whenSpacesSurroundEquals_shouldMaskValue() {
            assertThat(masker.mask("snils = 12345678900")).isEqualTo("snils = ***");
        }

        @Test
        @DisplayName("A dot separates names: user.email matches email")
        void whenNameIsDotted_shouldMaskByLastSegment() {
            assertThat(masker.mask("user.email=ivan@example.com")).isEqualTo("user.email=***");
        }

        @Test
        @DisplayName("Does not confuse comparisons with assignments")
        void whenLineContainsComparisonOperators_shouldReturnLineUnchanged() {
            var line = PREFIX + "check failed: count >= 10, retries == 3";

            assertThat(masker.mask(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("null and empty values remain unchanged")
        void whenValueIsNullOrEmpty_shouldReturnLineUnchanged() {
            assertThat(masker.mask("Customer(snils=null, id=5)")).isEqualTo("Customer(snils=null, id=5)");
            assertThat(masker.mask("phone=,next=1")).isEqualTo("phone=,next=1");
            assertThat(masker.mask("{\"snils\":null}")).isEqualTo("{\"snils\":null}");
        }

        @Test
        @DisplayName("Known limitation: a comma ends a plain-text value")
        void whenValueContainsComma_shouldMaskOnlyPartBeforeComma() {
            var masked = masker.mask("password=Hello, John");

            assertThat(masked)
                    .as("The prefix is masked; text after the comma remains outside the value")
                    .isEqualTo("password=***, John");
        }
    }

    @Nested
    @DisplayName("Unnamed values are outside the layer's scope")
    class ValueWithoutKey {

        @Test
        @DisplayName("Free-text email and phone values remain outside name-based matching")
        void whenEmailAndPhoneHaveNoFieldName_shouldReturnLineUnchanged() {
            var line = PREFIX + "Notification sent to ivan.petrov@example.com and +7 916 123-45-67";

            assertThat(masker.mask(line))
                    .as("Matching requires a field name; unnamed values need explicit masking")
                    .isEqualTo(line);
        }

        @Test
        @DisplayName("A full name without a key cannot be matched")
        void whenFullNameHasNoFieldName_shouldReturnLineUnchanged() {
            var line = PREFIX + "Could not update customer John Smith";

            assertThat(masker.mask(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("The same value is masked when accompanied by a field name")
        void whenSameValueHasFieldName_shouldMaskValue() {
            assertThat(masker.mask(PREFIX + "email=ivan.petrov@example.com"))
                    .isEqualTo(PREFIX + "email=***");
        }
    }

    @Nested
    @DisplayName("Ordinary line behavior")
    class FastPath {

        @Test
        @DisplayName("Unmatched lines retain object identity for the encoder fast path")
        void whenLineHasNoMaskedField_shouldReturnSameInstance() {
            var line = PREFIX + "Started SampleApplication in 2.431 seconds";

            assertThat(masker.mask(line)).isSameAs(line);
        }

        @Test
        void whenLineIsNullOrEmpty_shouldReturnItUnchanged() {
            assertThat(masker.mask(null)).isNull();
            assertThat(masker.mask("")).isEmpty();
        }

        @Test
        @DisplayName("Masks sensitive fields throughout a multiline stack trace")
        void whenLineIsMultilineStackTrace_shouldMaskAcrossAllLines() {
            var line = PREFIX + "Validation error\n"
                    + "com.example.ValidationException: rejected value snils=12345678900\n"
                    + "\tat com.example.Service.update(Service.java:42)\n";

            var masked = masker.mask(line);

            assertThat(masked).doesNotContain("12345678900").contains("snils=***");
            assertThat(masked).as("Preserves stack trace structure").contains("at com.example.Service.update");
        }

        @Test
        @DisplayName("An unmatched quote does not discard the rest of the line")
        void whenLineHasUnbalancedQuoteAndNoMaskedField_shouldReturnLineUnchanged() {
            var line = PREFIX + "received \"start without end";

            assertThat(masker.mask(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("An unmatched quote does not disable subsequent masking")
        void whenUnbalancedQuotePrecedesMaskedFields_shouldStillMaskThem() {
            var line = PREFIX + "Cannot parse value \" at index 3; snils=12345678900, phone=79001112233";

            assertThat(masker.mask(line))
                    .doesNotContain("12345678900", "79001112233")
                    .contains("snils=***", "phone=***");
        }
    }

    /**
     * Regression coverage for personal data inside quoted fragments, including parsing errors and
     * structured encoder messages.
     */
    @Nested
    @DisplayName("Sensitive data inside quoted fragments")
    class InsideQuotedText {

        @Test
        @DisplayName("Structured encoder message strings are scanned for sensitive fields")
        void whenFieldIsInsideJsonMessageString_shouldMaskValue() {
            var line = "{\"level\":\"INFO\",\"message\":\"login for phone=79001234567\",\"thread\":\"t1\"}";

            assertThat(masker.mask(line))
                    .isEqualTo("{\"level\":\"INFO\",\"message\":\"login for phone=***\",\"thread\":\"t1\"}");
        }

        @Test
        @DisplayName("Preserves the enclosing quote so JSON remains parseable")
        void whenValueIsInsideQuotedText_shouldMaskItAndKeepClosingQuote() {
            var line = "{\"@timestamp\":\"2026-09-01\","
                    + "\"message\":\"ProfileUpdateRequest(snils=12345678900, position=engineer)\","
                    + "\"logger\":\"c.S\"}";

            var masked = masker.mask(line);

            assertThat(masked).doesNotContain("12345678900").contains("snils=***");
            assertThat(masked).as("Preserves neighboring log entry fields")
                    .contains("\"logger\":\"c.S\"", "\"@timestamp\":\"2026-09-01\"", "position=engineer");
        }

        @Test
        @DisplayName("Stops at the enclosing quote rather than the end of the log entry")
        void whenQuoteEndsValue_shouldMaskUntilClosingQuoteOnly() {
            var line = "{\"message\":\"fullName=John Smith\",\"level\":\"INFO\"}";

            assertThat(masker.mask(line))
                    .isEqualTo("{\"message\":\"fullName=***\",\"level\":\"INFO\"}");
        }
    }

    /**
     * Bare colon-separated names are recognized only for configured secret headers. Ordinary labels can
     * introduce prose without a reliable boundary, so masking could consume the rest of the message.
     */
    @Nested
    @DisplayName("Colon-separated headers")
    class ColonSeparatedHeader {

        @Test
        @DisplayName("Masks colon-separated Authorization values emitted by HTTP clients")
        void whenAuthorizationWrittenWithColon_shouldMaskValue() {
            assertThat(masker.mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token"))
                    .isEqualTo("Authorization: ***");
        }

        @Test
        @DisplayName("Masks the other configured headers regardless of case or spacing")
        void whenOtherSecretHeadersWrittenWithColon_shouldMaskValueInAnyCase() {
            assertThat(masker.mask("Set-Cookie: SESSION=abc123; Path=/; HttpOnly"))
                    .isEqualTo("Set-Cookie: ***");
            assertThat(masker.mask("x-auth-token:s-7781-abc")).isEqualTo("x-auth-token:***");
            assertThat(masker.mask("Headers: [Authorization: Bearer eyJ, Content-Type: application/json]"))
                    .isEqualTo("Headers: [Authorization: ***, Content-Type: application/json]");
        }

        @Test
        @DisplayName("Leaves ordinary colon labels unchanged to preserve message prose")
        void whenOrdinaryFieldWrittenWithColon_shouldReturnLineUnchanged() {
            assertThat(masker.mask("email: required field; check the input format"))
                    .isEqualTo("email: required field; check the input format");
            assertThat(masker.mask("password: text is too long (maximum 255 characters)"))
                    .isEqualTo("password: text is too long (maximum 255 characters)");
        }

        @Test
        @DisplayName("Preserves colons in timestamps, stack traces and URLs")
        void whenColonIsStructural_shouldReturnSameInstance() {
            var lines = List.of(
                    "2026-09-01 12:03:44.120 INFO com.example.Service - Validation error: missing field",
                    "at com.example.Service.update(Service.java:42)",
                    "Tomcat started on port 8080 (http) with context path ''");

            lines.forEach(line -> assertThat(masker.mask(line)).isSameAs(line));
        }

        @Test
        @DisplayName("Quoted JSON keys still work alongside bare header matching")
        void whenJsonKeyIsQuoted_shouldMaskValue() {
            assertThat(masker.mask("{\"level\":\"INFO\",\"snils\":\"12345678900\"}"))
                    .isEqualTo("{\"level\":\"INFO\",\"snils\":\"***\"}");
        }
    }

    @Nested
    @DisplayName("Field name lookup")
    class NameLookup {

        @Test
        @DisplayName("Matching is case-insensitive in every supported form")
        void whenNameCaseDiffers_shouldMaskValue() {
            assertThat(masker.mask("SNILS=12345678900")).isEqualTo("SNILS=***");
            assertThat(masker.mask("SnIlS=12345678900")).isEqualTo("SnIlS=***");
            assertThat(masker.mask("{\"SNILS\":\"12345678900\"}")).isEqualTo("{\"SNILS\":\"***\"}");
        }

        @Test
        @DisplayName("Matches complete names without confusing similar names")
        void whenNameOnlyPartiallyMatches_shouldMaskExactMatchesOnly() {
            var line = "snilsCheck=1, mysnils=2, snils=12345678900, snil=4";

            assertThat(masker.mask(line)).isEqualTo("snilsCheck=1, mysnils=2, snils=***, snil=4");
        }

        @Test
        @DisplayName("Finds configured non-Latin names too")
        void whenNameIsNonAscii_shouldMaskValue() {
            var withCyrillic = new LineMasker(new MaskingPolicyRegistry(
                    List.of(new DefaultMaskingPolicy(), new MaskFieldIndex(List.of("\u0424\u0430\u043c\u0438\u043b\u0438\u044f"), 1))));

            assertThat(withCyrillic.mask("\u0444\u0430\u043c\u0438\u043b\u0438\u044f=\u0418\u0432\u0430\u043d\u043e\u0432, id=5")).isEqualTo("\u0444\u0430\u043c\u0438\u043b\u0438\u044f=***, id=5");
        }

        @Test
        @DisplayName("Rejects names longer than every indexed name before lookup")
        void whenNameIsLongerThanAnyKnown_shouldReturnSameInstance() {
            var line = "a".repeat(200) + "=value";

            assertThat(masker.mask(line)).isSameAs(line);
        }
    }

    @Nested
    @DisplayName("Global strategy groups")
    class OnLine {

        private final LineMasker masker = new LineMasker(new MaskingPolicyRegistry(List.of(new GroupedPolicy())));

        @Test
        @DisplayName("Plain pairs use each field strategy and preserve neighbors")
        void whenLineHasFieldsFromSeveralGroups_shouldApplyStrategyPerGroup() {
            var line = "CustomerDto(snils=12345678900, accessToken=John Smith, passportNumber=250000, attempt=3)";

            assertThat(masker.mask(line))
                    .isEqualTo("CustomerDto(snils=***8900, accessToken=#" + hashOf("John Smith")
                            + ", passportNumber=***, attempt=3)");
        }

        @Test
        @DisplayName("JSON quotes are removed before applying LAST4")
        void whenJsonValueIsQuoted_shouldUnquoteBeforeApplyingStrategy() {
            assertThat(masker.mask("{\"snils\":\"12345678900\",\"id\":7}"))
                    .isEqualTo("{\"snils\":\"***8900\",\"id\":7}");
        }

        @Test
        @DisplayName("Unwraps single-quoted values before applying a strategy")
        void whenValueIsSingleQuoted_shouldUnquoteBeforeApplyingStrategy() {
            assertThat(masker.mask("GET /sap?Snils='12345678900'&page=0"))
                    .isEqualTo("GET /sap?Snils=***8900&page=0");
        }

        @Test
        @DisplayName("Equal values in different lines yield the same correlation hash")
        void whenSameValueAppearsInDifferentLines_shouldProduceSameHash() {
            var first = masker.mask("accessToken=John Smith, action=reject");
            var second = masker.mask("{\"accessToken\":\"John Smith\"}");
            var other = masker.mask("accessToken=Peter Jones, action=reject");

            var hash = "#" + hashOf("John Smith");
            assertThat(first).contains(hash);
            assertThat(second).contains(hash);
            assertThat(other).doesNotContain(hash);
        }

        @Test
        @DisplayName("Secret headers remain fully masked alongside strategy groups")
        void whenSecretHeaderAppearsBesideGroupedFields_shouldMaskItCompletely() {
            assertThat(masker.mask("Headers: [Authorization=Bearer eyJhbGciOi, X-Auth-Token=s-7781-abc]"))
                    .isEqualTo("Headers: [Authorization=***, X-Auth-Token=***]");
        }

        @Test
        @DisplayName("A value already in HASH form is not hashed again")
        void whenHashFieldAlreadyHoldsHash_shouldKeepIt() {
            var hash = "#" + hashOf("John Smith");
            var plain = "accessToken=" + hash + ", action=reject";
            var json = "{\"accessToken\":\"" + hash + "\",\"id\":7}";

            assertThat(masker.mask(plain)).isSameAs(plain);
            assertThat(masker.mask(json)).isSameAs(json);
        }

        @Test
        @DisplayName("Only the exact HASH form is left unchanged")
        void whenValueOnlyResemblesHash_shouldHashIt() {
            assertThat(masker.mask("accessToken=#EF61A579")).doesNotContain("EF61A579");
            assertThat(masker.mask("accessToken=#ef61a57")).doesNotContain("ef61a57");
            assertThat(masker.mask("accessToken=#ef61a5790")).doesNotContain("ef61a5790");
            assertThat(masker.mask("passportNumber=#ef61a579"))
                    .as("Fields with other strategies mask hash-shaped values as usual")
                    .isEqualTo("passportNumber=***");
        }

        private String hashOf(String value) {
            return MaskingFunctions.apply(MaskStrategy.HASH, value).substring(1);
        }
    }

    @Nested
    @DisplayName("Fields already masked by LogMasker")
    class AlreadyMaskedFields {

        private final LineMasker masker = new LineMasker(
                new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy(), new GroupedPolicy())));

        @Test
        @DisplayName("Message text after a field argument stays visible")
        void whenFieldArgumentIsFollowedByText_shouldKeepTheText() {
            var email = new MaskedField("email", "user@example.com", MaskStrategy.FULL);
            var line = PREFIX + "user " + email + " logged in";

            assertThat(masker.mask(line, new Object[] {email})).isSameAs(line);
            assertThat(masker.mask(line))
                    .as("Without the argument, the plain-text boundary takes the rest of the line")
                    .isEqualTo(PREFIX + "user email=***");
        }

        @Test
        @DisplayName("Every field of a multi-argument message is kept")
        void whenSeveralFieldArguments_shouldKeepEach() {
            var email = new MaskedField("email", "user@example.com", MaskStrategy.FULL);
            var phone = new MaskedField("phone", "+7 900 123 45 67", MaskStrategy.FULL);
            var line = "request " + email + " " + phone;

            assertThat(masker.mask(line, new Object[] {email, phone})).isEqualTo("request email=*** phone=***");
        }

        @Test
        @DisplayName("A hashed field is not hashed again")
        void whenFieldArgumentIsHashed_shouldKeepItsHash() {
            var token = new MaskedField("accessToken", "John Smith", MaskStrategy.HASH);
            var line = "token " + token + " expired";

            assertThat(masker.mask(line, new Object[] {token}))
                    .isEqualTo("token accessToken=" + MaskingFunctions.apply(MaskStrategy.HASH, "John Smith")
                            + " expired");
        }

        @Test
        @DisplayName("The member strategy survives a different global strategy")
        void whenFieldStrategyDiffersFromGlobal_shouldKeepFieldStrategy() {
            var phone = new MaskedField("phone", "+79001234567", MaskStrategy.LAST4);
            var snils = new MaskedField("snils", "12345678900", MaskStrategy.HASH);
            var line = phone + ", " + snils;

            assertThat(masker.mask(line, new Object[] {phone, snils}))
                    .isEqualTo("phone=***4567, snils=" + MaskingFunctions.apply(MaskStrategy.HASH, "12345678900"));
        }

        @Test
        @DisplayName("A secret header stays fully masked even if the member asks for a partial strategy")
        void whenHeaderFieldHasPartialStrategy_shouldMaskItFully() {
            var partial = new MaskedField("authorization", "Bearer abcdef", MaskStrategy.LAST4);
            var full = new MaskedField("authorization", "Bearer abcdef", MaskStrategy.FULL);

            assertThat(masker.mask("call " + partial + ", status=401", new Object[] {partial}))
                    .isEqualTo("call authorization=***, status=401");
            assertThat(masker.mask("call " + full + " failed", new Object[] {full}))
                    .isEqualTo("call authorization=*** failed");
        }

        @Test
        @DisplayName("Raw values in the same line are still masked")
        void whenLineAlsoHasRawValues_shouldMaskThem() {
            var email = new MaskedField("email", "user@example.com", MaskStrategy.FULL);
            var line = "user " + email + ", fullName=John Smith, email=other@example.com";

            assertThat(masker.mask(line, new Object[] {email}))
                    .isEqualTo("user email=***, fullName=***, email=***");
        }

        @Test
        @DisplayName("Text that only starts like the rendered field is masked")
        void whenRawValueStartsLikeRenderedField_shouldMaskIt() {
            var email = new MaskedField("email", "user@example.com", MaskStrategy.FULL);

            assertThat(masker.mask("email=***secret", new Object[] {email})).isEqualTo("email=***");
        }

        @Test
        @DisplayName("Only MaskedField arguments are trusted, not equal strings")
        void whenArgumentIsPlainString_shouldMaskAsUsual() {
            var line = "user email=*** logged in";

            assertThat(masker.mask(line, new Object[] {"email=***", 42})).isEqualTo("user email=***");
        }
    }

    /**
     * Combines fully masked names with explicit strategy groups.
     */
    private static final class GroupedPolicy implements MaskingPolicy {

        @Override
        public Set<String> maskedFields() {
            return Set.of("snils", "accessToken", "passportNumber");
        }

        @Override
        public Set<String> secretHeaders() {
            return Set.of("authorization", "x-auth-token");
        }

        @Override
        public Map<String, MaskStrategy> fieldStrategies() {
            return Map.of("snils", MaskStrategy.LAST4, "accessToken", MaskStrategy.HASH);
        }
    }
}

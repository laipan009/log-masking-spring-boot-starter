package io.github.laipan009.logmasking.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

@DisplayName("LogMasker")
class LogMaskerTest {

    private static final String FIO = "John Smith";
    private static final String SNILS = "12345678900";

    private final MaskingPolicyRegistry registry = new MaskingPolicyRegistry(List.of(new TestPolicy()));
    private final PayloadSanitizer sanitizer = new PayloadSanitizer(new ObjectMapper(), registry);
    private final LogMasker masker = new LogMasker(sanitizer, registry);

    private final ListAppender<ILoggingEvent> warnings = new ListAppender<>();
    private final ch.qos.logback.classic.Logger maskerLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LogMasker.class);

    @BeforeEach
    void captureWarnings() {
        warnings.start();
        maskerLog.addAppender(warnings);
    }

    @AfterEach
    void releaseWarnings() {
        maskerLog.detachAppender(warnings);
    }

    @Nested
    @DisplayName("field(bean, Dto::getFio)")
    class FieldByGetter {

        private final Customer customer = new Customer(FIO, SNILS);

        @Test
        void whenGetterNameIsListed_shouldRenderNameAndMask() {
            assertThat(masker.field(customer, Customer::getFio)).hasToString("fio=***");
        }

        @Test
        @DisplayName("Record component with registry LAST4 strategy")
        void whenRecordComponentHasRegistryStrategy_shouldApplyIt() {
            var rec = new Rec(SNILS, FIO);

            assertThat(masker.field(rec, Rec::snils)).hasToString("snils=***8900");
        }

        @Test
        @DisplayName("Field-level @JsonProperty supplies the name for registry lookup")
        void whenFieldHasJsonProperty_shouldUseJsonNameForLookupAndRender() {
            var dto = new JsonPropertyDto("Smith");

            assertThat(masker.field(dto, JsonPropertyDto::getLastName)).hasToString("last_name=***");
        }

        @Test
        @DisplayName("Class-level @JsonNaming(SnakeCase): getLastName -> last_name")
        void whenClassHasJsonNaming_shouldTranslateName() {
            var dto = new SnakeDto("Smith", "John");

            assertThat(masker.field(dto, SnakeDto::getLastName)).hasToString("last_name=***");
        }

        @Test
        @DisplayName("Java registry name resolves a snake_case member without a warning")
        void whenRegistryListsJavaSpellingButClassIsSnakeCase_shouldStillFindStrategy() {
            var dto = new SnakeDto("Smith", "John");

            var field = masker.field(dto, SnakeDto::getFullName);

            assertThat(field).hasToString("full_name=***");
            assertThat(warnings.list).isEmpty();
        }

        @Test
        @DisplayName("@Mask(scope = TYPED) applies here but not in LineMasker")
        void whenMemberHasTypedMask_shouldMaskOnlyThroughField() {
            var dto = new TypedDto("Hello");

            assertThat(masker.field(dto, TypedDto::getComment)).hasToString("comment=***");
            assertThat(new LineMasker(registry).mask("comment=Hello"))
                    .as("TYPED fields are excluded from the global index")
                    .isEqualTo("comment=Hello");
        }

        @Test
        @DisplayName("Member-level @Mask(strategy = HASH) overrides registry LAST4")
        void whenMemberMaskAndRegistryDisagree_shouldPreferAnnotation() {
            var dto = new HashDto(SNILS);

            assertThat(masker.field(dto, HashDto::getSnils).toString()).startsWith("snils=#").hasSize("snils=#".length() + 8);
        }

        @Test
        @DisplayName("An unlisted, unannotated field is fully masked with one warning per call site")
        void whenNameIsUnlisted_shouldMaskFullyAndWarnOncePerCallSite() {
            var dto = new Position("P-42");

            for (var i = 0; i < 3; i++) {
                assertThat(masker.field(dto, Position::getPositionId)).hasToString("positionId=***");
            }

            assertThat(warnings.list).hasSize(1);
            assertThat(warnings.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(warnings.list.get(0).getFormattedMessage())
                    .isEqualTo("Field positionId is not in the masking policy; masked entirely. "
                            + "Add @Mask or logging.masking.additional-masked-fields");
        }

        @Test
        void whenValueIsNull_shouldRenderNullWithoutMask() {
            assertThat(masker.field(new Customer(null, SNILS), Customer::getFio)).hasToString("fio=null");
        }

        @Test
        void whenBeanIsNull_shouldRenderNullWithoutCallingGetter() {
            assertThat(masker.field((Customer) null, Customer::getFio)).hasToString("fio=null");
        }

        @Test
        void whenLambdaHasBody_shouldThrow() {
            assertThatThrownBy(() -> masker.field(customer, e -> e.getFio()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected a method reference");
        }

        @Test
        @DisplayName("A bound service::find reference is not a getter reference")
        void whenReferenceIsBound_shouldThrow() {
            var lookup = new Lookup();

            assertThatThrownBy(() -> masker.field(customer, lookup::find))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected a method reference");
        }

        @Test
        @DisplayName("A handwritten function class produces a clear argument error")
        void whenFunctionIsHandWrittenClass_shouldThrow() {
            assertThatThrownBy(() -> masker.field(customer, new Handwritten()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected a method reference");
        }

        @Test
        @DisplayName("Two named fields in one log line")
        void whenTwoFieldsInOneLine_shouldRenderBoth() {
            var line = MessageFormatter.arrayFormat("request {} {}",
                    new Object[]{masker.field(customer, Customer::getFio), masker.field(customer, Customer::getSnils)})
                    .getMessage();

            assertThat(line).isEqualTo("request fio=*** snils=***8900");
        }

        @Test
        @DisplayName("Getter reference through an interface")
        void whenGetterIsDeclaredOnInterface_shouldResolveName() {
            HasFio viaInterface = customer;

            assertThat(masker.field(viaInterface, HasFio::getFio)).hasToString("fio=***");
        }

        @Test
        @DisplayName("Interface default method invoked through a class")
        void whenGetterIsInterfaceDefaultMethod_shouldResolveName() {
            assertThat(masker.field(new Defaulted(), Defaulted::getFio)).hasToString("fio=***");
        }

        @Test
        @DisplayName("Inherited getter with @Mask on the base class")
        void whenGetterIsInheritedWithMask_shouldApplyAnnotationWithoutWarning() {
            var field = masker.field(new InheritedDto("secret"), InheritedDto::getInheritedSecret);

            assertThat(field).hasToString("inheritedSecret=***");
            assertThat(warnings.list).isEmpty();
        }

        @Test
        @DisplayName("Masking is lazy: field() does not inspect the value before toString()")
        void whenValueToStringThrows_shouldFailOnlyAtRender() {
            var field = masker.field(new Bomb(), Bomb::getFio);

            assertThatThrownBy(field::toString).isInstanceOf(IllegalStateException.class).hasMessage("early");
        }

        @Test
        void whenRendered_shouldExposeNameAndMaskedValue() {
            var field = masker.field(customer, Customer::getSnils);

            assertThat(field.name()).isEqualTo("snils");
            assertThat(field.value()).isEqualTo("***8900");
        }

        @Test
        @DisplayName("Repeated calls from one site reuse cached reflection")
        void whenCalledTwiceFromOneCallSite_shouldIntrospectOnce() {
            var resolver = spy(new MethodReferenceResolver(registry, null, name -> { }));
            var cached = new LogMasker(sanitizer, registry, resolver);

            for (var i = 0; i < 2; i++) {
                cached.field(customer, Customer::getFio);
            }

            then(resolver).should(times(1)).introspect(any());
        }

        @Test
        @DisplayName("Global ObjectMapper naming: getFullName -> full_name without @JsonNaming")
        void whenGlobalNamingIsSnakeCase_shouldTranslateNameAndFindStrategy() {
            var snakeMasker = new LogMasker(sanitizer, registry, new PropertyNamingStrategies.SnakeCaseStrategy());

            var field = snakeMasker.field(customer, Customer::getFullName);

            assertThat(field).hasToString("full_name=***");
            assertThat(warnings.list).isEmpty();
        }

        @Test
        @DisplayName("Boolean record component reference resolves its @Mask annotation")
        void whenRecordComponentIsBooleanGetterStyle_shouldFindAnnotationByMethodName() {
            var field = masker.field(new Flag(true), Flag::isActive);

            assertThat(field).hasToString("active=***");
            assertThat(warnings.list).isEmpty();
        }

        @Test
        void whenGetterIsNull_shouldThrow() {
            assertThatThrownBy(() -> masker.field(customer, (SerializableFunction<Customer, ?>) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected a method reference");
        }
    }

    @Nested
    @DisplayName("field(name, value)")
    class FieldByName {

        @Test
        void whenNameIsListed_shouldMaskByRegistry() {
            assertThat(masker.field("fio", FIO)).hasToString("fio=***");
            assertThat(masker.field("snils", SNILS)).hasToString("snils=***8900");
        }

        @Test
        void whenNameIsBlank_shouldThrow() {
            assertThatThrownBy(() -> masker.field("", FIO)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> masker.field(null, FIO)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Three calls for an unknown name produce one warning and full masking")
        void whenNameIsUnlisted_shouldMaskFullyAndWarnOncePerName() {
            for (var i = 0; i < 3; i++) {
                assertThat(masker.field("positionId", "P-42")).hasToString("positionId=***");
            }

            assertThat(warnings.list).hasSize(1);
            assertThat(warnings.list.get(0).getFormattedMessage()).contains("positionId");
        }

        @Test
        @DisplayName("Secret headers are fully masked without a warning")
        void whenNameIsSecretHeader_shouldMaskFullyWithoutWarning() {
            assertThat(masker.field("Authorization", "Bearer eyJ")).hasToString("Authorization=***");
            assertThat(warnings.list).isEmpty();
        }

        @Test
        void whenValueIsNull_shouldRenderNull() {
            assertThat(masker.field("fio", (Object) null)).hasToString("fio=null");
        }
    }

    @Nested
    @DisplayName("Public API")
    class Api {

        @Test
        @DisplayName("Removed mask(Object) and mask(JsonNode) overloads reject unnamed values")
        void whenInspected_shouldHaveNoMaskMethods() {
            assertThat(Arrays.stream(LogMasker.class.getDeclaredMethods()).map(Method::getName))
                    .doesNotContain("mask");
        }

        @Test
        void whenJsonGiven_shouldMaskAsBefore() {
            assertThat(masker.maskJson("{\"snils\":\"123\"}")).isEqualTo("{\"snils\":\"***\"}");
        }
    }

    private static final class TestPolicy implements MaskingPolicy {

        @Override
        public Set<String> maskedFields() {
            return Set.of("fio", "snils", "last_name", "fullName");
        }

        @Override
        public Map<String, MaskStrategy> fieldStrategies() {
            return Map.of("snils", MaskStrategy.LAST4);
        }

        @Override
        public Set<String> secretHeaders() {
            return Set.of("authorization");
        }
    }

    interface HasFio {

        String getFio();
    }

    interface WithFio {

        default String getFio() {
            return FIO;
        }
    }

    static final class Defaulted implements WithFio {
    }

    /**
     * Models a Lombok-style class with private fields and getters.
     */
    static final class Customer implements HasFio {

        private final String fio;
        private final String snils;

        Customer(String fio, String snils) {
            this.fio = fio;
            this.snils = snils;
        }

        @Override
        public String getFio() {
            return fio;
        }

        public String getSnils() {
            return snils;
        }

        public String getFullName() {
            return fio;
        }
    }

    record Flag(@Mask(scope = MaskScope.TYPED) boolean isActive) {
    }

    record Rec(String snils, String fio) {
    }

    static final class JsonPropertyDto {

        @JsonProperty("last_name")
        private final String lastName;

        JsonPropertyDto(String lastName) {
            this.lastName = lastName;
        }

        public String getLastName() {
            return lastName;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    static final class SnakeDto {

        private final String lastName;
        private final String fullName;

        SnakeDto(String lastName, String fullName) {
            this.lastName = lastName;
            this.fullName = fullName;
        }

        public String getLastName() {
            return lastName;
        }

        public String getFullName() {
            return fullName;
        }
    }

    static final class TypedDto {

        @Mask(scope = MaskScope.TYPED)
        private final String comment;

        TypedDto(String comment) {
            this.comment = comment;
        }

        public String getComment() {
            return comment;
        }
    }

    static final class HashDto {

        @Mask(strategy = MaskStrategy.HASH)
        private final String snils;

        HashDto(String snils) {
            this.snils = snils;
        }

        public String getSnils() {
            return snils;
        }
    }

    static final class Position {

        private final String positionId;

        Position(String positionId) {
            this.positionId = positionId;
        }

        public String getPositionId() {
            return positionId;
        }
    }

    static class BaseDto {

        private final String inheritedSecret;

        BaseDto(String inheritedSecret) {
            this.inheritedSecret = inheritedSecret;
        }

        @Mask
        public String getInheritedSecret() {
            return inheritedSecret;
        }
    }

    static final class InheritedDto extends BaseDto {

        InheritedDto(String inheritedSecret) {
            super(inheritedSecret);
        }
    }

    static final class Bomb {

        public Object getFio() {
            return new Object() {
                @Override
                public String toString() {
                    throw new IllegalStateException("early");
                }
            };
        }
    }

    static final class Lookup {

        public String find(Customer customer) {
            return customer.getFio();
        }
    }

    static final class Handwritten implements SerializableFunction<Customer, String> {

        @Override
        public String apply(Customer customer) {
            return customer.getFio();
        }
    }
}

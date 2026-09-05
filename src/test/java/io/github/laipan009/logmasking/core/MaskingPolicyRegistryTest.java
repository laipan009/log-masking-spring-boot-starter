package io.github.laipan009.logmasking.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaskingPolicyRegistry: policy merging")
class MaskingPolicyRegistryTest {

    @Test
    @DisplayName("Explicit strategies override implicit full masking from field lists")
    void strategyFor_whenExplicitStrategyMeetsDefaultMaskedField_shouldReturnExplicitStrategy() {
        var weakFirst = new MaskingPolicyRegistry(List.of(new LooseSnils(), new StrictSnils()));
        var strictFirst = new MaskingPolicyRegistry(List.of(new StrictSnils(), new LooseSnils()));

        assertThat(weakFirst.strategyFor("snils")).isEqualTo(MaskStrategy.LAST4);
        assertThat(strictFirst.strategyFor("snils"))
                .as("Policy ordering does not affect the result")
                .isEqualTo(MaskStrategy.LAST4);
    }

    @Test
    @DisplayName("The more restrictive explicit strategy wins")
    void strategyFor_whenTwoExplicitStrategiesConflict_shouldReturnStrongerStrategy() {
        var registry = new MaskingPolicyRegistry(List.of(new LooseSnils(), new HashedSnils()));

        assertThat(registry.strategyFor("snils")).isEqualTo(MaskStrategy.HASH);
    }

    @Test
    @DisplayName("An explicit strategy overrides implicit full masking within one policy")
    void strategyFor_whenSinglePolicyDeclaresFieldBothWays_shouldReturnItsExplicitStrategy() {
        assertThat(new MaskingPolicyRegistry(List.of(new GroupedPolicy())).strategyFor("snils"))
                .isEqualTo(MaskStrategy.LAST4);
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "LAST4, HASH,  HASH",
            "HASH,  LAST4, HASH",
            "LAST4, FULL,  FULL",
            "FULL,  LAST4, FULL",
            "HASH,  FULL,  FULL",
            "FULL,  HASH,  FULL",
            "LAST4, LAST4, LAST4",
            "HASH,  HASH,  HASH",
            "FULL,  FULL,  FULL"})
    @DisplayName("Strategy priority is FULL > HASH > LAST4 in either argument order")
    void strategyFor_whenAnyStrategyPairConflicts_shouldReturnStrongestRegardlessOfOrder(
            MaskStrategy left, MaskStrategy right, MaskStrategy expected) {
        var registry = new MaskingPolicyRegistry(List.of(withSnils(left), withSnils(right)));

        assertThat(registry.strategyFor("snils")).isEqualTo(expected);
    }

    @Test
    @DisplayName("Secret headers always use full masking regardless of field strategies")
    void strategyFor_whenSecretHeaderHasExplicitStrategy_shouldNotPreventFullMasking() {
        var policy = new MaskingPolicy() {
            @Override
            public Set<String> secretHeaders() {
                return Set.of("authorization");
            }

            @Override
            public Map<String, MaskStrategy> fieldStrategies() {
                return Map.of("authorization", MaskStrategy.LAST4);
            }
        };
        var lineMasker = new LineMasker(new MaskingPolicyRegistry(List.of(policy)));

        assertThat(new MaskingPolicyRegistry(List.of(policy)).strategyFor("authorization"))
                .as("The registry must contain the strategy for this test to exercise it")
                .isEqualTo(MaskStrategy.LAST4);
        assertThat(lineMasker.mask("Headers: [Authorization=Bearer eyJhbGciOi9876]"))
                .isEqualTo("Headers: [Authorization=***]");
    }

    @Test
    void strategyFor_whenFieldNotDeclaredByAnyPolicy_shouldReturnNull() {
        assertThat(new MaskingPolicyRegistry(List.of(new GroupedPolicy())).strategyFor("attempt")).isNull();
    }

    private MaskingPolicy withSnils(MaskStrategy strategy) {
        return new MaskingPolicy() {
            @Override
            public Map<String, MaskStrategy> fieldStrategies() {
                return Map.of("snils", strategy);
            }
        };
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

    private static final class LooseSnils implements MaskingPolicy {

        @Override
        public Map<String, MaskStrategy> fieldStrategies() {
            return Map.of("snils", MaskStrategy.LAST4);
        }
    }

    private static final class HashedSnils implements MaskingPolicy {

        @Override
        public Map<String, MaskStrategy> fieldStrategies() {
            return Map.of("snils", MaskStrategy.HASH);
        }
    }

    private static final class StrictSnils implements MaskingPolicy {

        @Override
        public Set<String> maskedFields() {
            return Set.of("snils");
        }
    }
}

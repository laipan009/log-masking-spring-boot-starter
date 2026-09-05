package io.github.laipan009.logmasking.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMaskingPolicyTest {
    private final DefaultMaskingPolicy policy = new DefaultMaskingPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"fullname", "firstname", "lastname", "birthdate", "email",
            "phone", "address", "passportnumber", "snils", "inn", "password", "token",
            "accesstoken", "refreshtoken", "apikey", "clientsecret"})
    void personalDataAndCredentialsAreMasked(String field) {
        assertThat(policy.maskedFields()).contains(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "message", "code", "value", "id", "status",
            "comment", "amount", "accountBalance", "verifiedIdentity", "customerId"})
    void applicationSpecificAndGenericFieldsRequireExplicitConfiguration(String field) {
        assertThat(policy.maskedFields()).doesNotContain(field.toLowerCase(java.util.Locale.ROOT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-auth-token", "x-csrf-token", "x-api-key"})
    void secretHeadersAreMasked(String header) {
        assertThat(policy.secretHeaders()).contains(header);
    }

    @Test
    void fieldNamesFitScannerConstraints() {
        assertThat(policy.maskedFields()).allSatisfy(field ->
                assertThat(field).matches("[a-z0-9]{1,64}"));
    }
}

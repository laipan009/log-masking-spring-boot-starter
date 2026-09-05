package io.github.laipan009.logmasking.core;

import java.util.Set;

/** Generic defaults. Applications must explicitly add their domain-specific fields. */
public final class DefaultMaskingPolicy implements MaskingPolicy {

    private static final Set<String> SECRET_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-auth-token", "x-csrf-token", "x-api-key");

    // Exact names, case-insensitive. This is not a detector for arbitrary personal data.
    private static final Set<String> MASKED_FIELDS = Set.of(
            "fullname", "firstname", "lastname", "middlename", "surname", "patronymic",
            "birthdate", "birthday", "email", "mail", "phone", "phonenumber",
            "address", "fulladdress", "addressdata", "passportnumber", "snils", "inn",
            "password", "passwd", "secret", "token", "accesstoken", "refreshtoken",
            "apikey", "clientsecret", "keypassword", "keystorepassword", "truststorepassword");

    @Override
    public Set<String> maskedFields() {
        return MASKED_FIELDS;
    }

    @Override
    public Set<String> secretHeaders() {
        return SECRET_HEADERS;
    }
}

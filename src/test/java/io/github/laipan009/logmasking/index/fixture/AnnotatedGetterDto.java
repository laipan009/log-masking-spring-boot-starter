package io.github.laipan009.logmasking.index.fixture;

import io.github.laipan009.logmasking.core.Mask;

public class AnnotatedGetterDto {

    private String passportNumber;

    @Mask
    public String getPassportNumber() {
        return passportNumber;
    }
}

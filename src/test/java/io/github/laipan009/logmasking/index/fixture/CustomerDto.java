package io.github.laipan009.logmasking.index.fixture;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.laipan009.logmasking.core.Mask;

public class CustomerDto {

    @Mask
    private String fullName;

    @Mask
    @JsonProperty("passport_number")
    private String passportNumber;

    private String position;

    public String getFullName() {
        return fullName;
    }

    public String getPassportNumber() {
        return passportNumber;
    }

    public String getPosition() {
        return position;
    }

    public static class Contacts {

        @Mask
        private String homePhone;

        public String getHomePhone() {
            return homePhone;
        }
    }
}

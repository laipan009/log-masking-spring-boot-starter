package io.github.laipan009.logmasking.index.fixture;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.github.laipan009.logmasking.core.Mask;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SnakeCaseDto {

    @Mask
    private String birthDate;

    public String getBirthDate() {
        return birthDate;
    }
}

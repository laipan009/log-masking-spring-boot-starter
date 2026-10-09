package io.github.laipan009.logmasking.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import io.github.laipan009.logmasking.core.LogMasker;
import io.github.laipan009.logmasking.sample.ProfileUpdateRequest;
import io.github.laipan009.logmasking.sample.CustomerResponse;
import io.github.laipan009.logmasking.sample.SampleApplication;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SampleApplication.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("LogMasker.field() from the application context")
class LogMaskerFieldIT {

    private static final Logger LOG = LoggerFactory.getLogger(LogMaskerFieldIT.class);

    private static final String CANARY_FULL_NAME = "KANAREYKA-FIO-8842";
    private static final String CANARY_NOTE = "KANAREYKA-NOTE-4471";

    @Autowired
    private LogMasker logMasker;

    @Test
    @DisplayName("Context bean wiring emits fullName=*** through field()")
    void whenListedFieldLogged_shouldMaskInStdout(CapturedOutput output) {
        var customer = new CustomerResponse("A-1", CANARY_FULL_NAME, "123-456-789 01");

        LOG.info("customer {}", logMasker.field(customer, CustomerResponse::fullName));

        assertThat(output.getOut()).contains("fullName=***").doesNotContain(CANARY_FULL_NAME);
    }

    @Test
    @DisplayName("Text after field() placeholders reaches stdout unchanged")
    void whenFieldsAreFollowedByText_shouldKeepTheText(CapturedOutput output) {
        var customer = new CustomerResponse("A-1", CANARY_FULL_NAME, "123-456-789 01");

        LOG.info("request {} {} accepted", logMasker.field(customer, CustomerResponse::fullName),
                logMasker.field(customer, CustomerResponse::snils));

        assertThat(output.getOut())
                .contains("request fullName=*** snils=*** accepted")
                .doesNotContain(CANARY_FULL_NAME)
                .doesNotContain("123-456-789 01");
    }

    @Test
    @DisplayName("@Mask(scope = TYPED) applies only through field(), not the encoder")
    void whenTypedFieldLogged_shouldMaskInStdoutThroughFieldOnly(CapturedOutput output) {
        var request = new ProfileUpdateRequest("T-1", "c", "a", "e@x", CANARY_NOTE);

        LOG.info("note {}", logMasker.field(request, ProfileUpdateRequest::internalNote));

        assertThat(output.getOut()).contains("internalNote=***").doesNotContain(CANARY_NOTE);
    }
}

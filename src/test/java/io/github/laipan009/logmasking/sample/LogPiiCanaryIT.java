package io.github.laipan009.logmasking.sample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage: ordinary application loggers emit synthetic personal data, which must not
 * reach stdout. The HTTP response retains its original values. Exercises argument dumps, query
 * strings, response bodies, headers and raw error payloads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Sensitive canary values do not reach stdout")
class LogPiiCanaryIT {

    private static final String CANARY_COMMENT = "KANAREYKA-COMMENT-7731";
    private static final String CANARY_FULL_NAME = "KANAREYKA-FIO-8842";
    private static final String CANARY_SNILS = "123-456-789 01";
    private static final String CANARY_ACTOR = "KANAREYKA-ACTOR-9953";
    private static final String CANARY_EMAIL = "kanareyka@example.com";
    private static final String CANARY_ALIAS = "KANAREYKA-ALIAS-6614";

    @Autowired
    private TestRestTemplate restTemplate;

    private void call(String taskId) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new ProfileUpdateRequest(
                taskId, CANARY_COMMENT, CANARY_ALIAS, CANARY_EMAIL, "internal-note-typed-only");

        var response = restTemplate.postForEntity(
                "/v1/profiles/{id}/update?actor={actor}",
                new HttpEntity<>(request, headers), CustomerResponse.class, "A-1", CANARY_ACTOR);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fullName())
                .as("The response retains original data; only the log is masked")
                .isEqualTo(CANARY_FULL_NAME);
    }

    @Test
    @DisplayName("Application-emitted sensitive values do not reach stdout")
    void shouldMaskEveryFormWrittenByTheApplicationItself(CapturedOutput output) {
        call("T-1");

        assertThat(output.getOut())
                .as("Argument dump: @Mask covers a name absent from defaults")
                .doesNotContain(CANARY_ALIAS)
                .as("comment is covered by the @Mask index")
                .doesNotContain(CANARY_COMMENT)
                .as("External response body in JSON form")
                .doesNotContain(CANARY_FULL_NAME)
                .doesNotContain(CANARY_SNILS)
                .as("Secret header in an external logger's output")
                .doesNotContain("canary-secret-token")
                .as("Email")
                .doesNotContain(CANARY_EMAIL);
    }

    @Test
    @DisplayName("Preserves neighboring parameters and the rest of the message")
    void shouldKeepEverythingElseReadable(CapturedOutput output) {
        call("T-2");

        assertThat(output.getOut())
                .as("Preserves the method and identifier for diagnostics")
                .contains("Start execution method - update()")
                .contains("A-1")
                .as("Preserves the next query parameter")
                .contains("page=0")
                .as("Preserves non-secret headers")
                .contains("Content-Type=application/json");
    }
}

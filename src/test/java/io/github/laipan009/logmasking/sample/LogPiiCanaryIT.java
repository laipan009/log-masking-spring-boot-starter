package io.github.laipan009.logmasking.sample;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage: ordinary application loggers emit synthetic personal data, which must not
 * reach stdout. The HTTP response retains its original values. Exercises argument dumps, query
 * strings, response bodies, headers, raw error payloads, MDC and exceptions.
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

    /**
     * Log storage can receive events other than through stdout: the OpenTelemetry agent captures them on
     * entry to {@code callAppenders}, and an appender without an encoder sends the event fields itself. An
     * appender without an encoder stands in for them here: it receives the same event object.
     */
    @Test
    @DisplayName("Consumers without an encoder (agents, Sentry, network appenders) receive the same masks")
    void shouldMaskEventsForConsumersWithoutEncoder() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var events = new ListAppender<ILoggingEvent>();
        events.setContext(context);
        events.start();
        var root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(events);
        List<ILoggingEvent> received;
        try {
            call("T-3");
        } finally {
            root.detachAppender(events);
            // doAppend synchronizes on the appender; under the same monitor the list is fully visible.
            synchronized (events) {
                received = List.copyOf(events.list);
            }
        }

        var seen = new ArrayList<String>();
        for (var event : received) {
            assertThat(event.getArgumentArray()).as("Arguments are unmasked and must not be exposed").isNull();
            seen.add(event.getFormattedMessage());
            seen.add(event.getMessage());
            seen.addAll(event.getMDCPropertyMap().values());
            for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                seen.add(proxy.getMessage());
            }
        }
        assertThat(String.join("\n", seen))
                .contains("Start execution method - update()", "CustomerDto(fullName=***)")
                .doesNotContain(CANARY_ALIAS, CANARY_COMMENT, CANARY_FULL_NAME, CANARY_SNILS, CANARY_EMAIL,
                        "canary-secret-token");
        assertThat(received).extracting(ILoggingEvent::getThrowableProxy).filteredOn(proxy -> proxy != null)
                .as("Sentry and OpenTelemetry extract the Throwable and receive a copy with the same masks")
                .isNotEmpty().allSatisfy(proxy -> assertThat(stackTraceOf(((ThrowableProxy) proxy).getThrowable()))
                        .contains("java.lang.IllegalStateException: CustomerDto(fullName=***)")
                        .doesNotContain(CANARY_FULL_NAME));
    }

    private static String stackTraceOf(Throwable throwable) {
        var out = new StringWriter();
        throwable.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}

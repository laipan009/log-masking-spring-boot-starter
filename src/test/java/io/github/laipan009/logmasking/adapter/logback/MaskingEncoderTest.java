package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.encoder.Encoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.github.laipan009.logmasking.core.DefaultMaskingPolicy;
import io.github.laipan009.logmasking.core.LineMasker;
import io.github.laipan009.logmasking.core.MaskingPolicyRegistry;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Checks delegate byte handling. Unmatched output must return the same byte array to avoid unnecessary
 * transcoding. The delegate charset must be respected even when it differs from the system charset,
 * including non-ASCII text.
 */
@DisplayName("MaskingEncoder: delegate encoder wrapper")
@ExtendWith(MockitoExtension.class)
class MaskingEncoderTest {

    private static final String PATTERN = "%msg%n";

    @Mock
    private Encoder<ILoggingEvent> delegate;

    private LoggerContext context;

    @BeforeEach
    void useDefaultPolicy() {
        MaskingLogbackSupport.useLineMasker(
                new LineMasker(new MaskingPolicyRegistry(List.of(new DefaultMaskingPolicy()))));
        context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
    }

    @AfterEach
    void releaseMasker() {
        MaskingLogbackSupport.useLineMasker(null);
    }

    @Nested
    @DisplayName("encode")
    class Encode {

        @Test
        @DisplayName("Returns the delegate bytes unchanged when no masking is needed")
        void whenNothingMasked_shouldReturnDelegateBytesUntouched() {
            // given
            var raw = "Started SampleApplication in 2.431 seconds\n".getBytes(Charset.defaultCharset());
            var event = event("irrelevant");
            given(delegate.encode(event)).willReturn(raw);

            // when
            var encoded = new MaskingEncoder(delegate).encode(event);

            // then
            assertThat(encoded)
                    .as("Reuses the same array for the encoder fast path")
                    .isSameAs(raw);
        }

        @Test
        @DisplayName("Returns new bytes when a sensitive value is masked")
        void whenValueMasked_shouldReturnRebuiltBytes() {
            // given
            var raw = "CustomerDto(snils=12345678900)\n".getBytes(Charset.defaultCharset());
            var event = event("irrelevant");
            given(delegate.encode(event)).willReturn(raw);

            // when
            var encoded = new MaskingEncoder(delegate).encode(event);

            // then
            assertThat(new String(encoded, Charset.defaultCharset())).isEqualTo("CustomerDto(snils=***)\n");
        }

        @Test
        @DisplayName("Uses the delegate charset to preserve non-ASCII text")
        void whenDelegateHasOwnCharset_shouldEncodeWithIt() {
            // given: the appender uses a charset different from the system default
            var cp1251 = Charset.forName("windows-1251");
            var event = event("Customer(fullName=\u0418\u0432\u0430\u043d\u043e\u0432 \u0418\u0432\u0430\u043d, snils=12345678900)");
            var encoder = new MaskingEncoder(startedPatternEncoder(cp1251));

            // when
            var encoded = encoder.encode(event);

            // then
            assertThat(new String(encoded, cp1251))
                    .isEqualTo("Customer(fullName=***, snils=***)" + System.lineSeparator());
        }

        @Test
        @DisplayName("Passes through absent delegate output without parsing")
        void whenDelegateReturnsNothing_shouldPassItThrough() {
            // given
            var event = event("irrelevant");
            var encoder = new MaskingEncoder(delegate);
            given(delegate.encode(event)).willReturn(null, new byte[0]);

            // when + then
            assertThat(encoder.encode(event)).isNull();
            assertThat(encoder.encode(event)).isEmpty();
        }
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("Starts a delegate that has not been started")
        void whenDelegateNotStarted_shouldStartIt() {
            // given
            given(delegate.isStarted()).willReturn(false);
            var encoder = new MaskingEncoder(delegate);

            // when
            encoder.start();

            // then
            verify(delegate).start();
            assertThat(encoder.isStarted()).isTrue();
        }

        @Test
        @DisplayName("Does not restart an already running delegate or reset its state")
        void whenDelegateAlreadyStarted_shouldNotStartItAgain() {
            // given: the installer wraps an encoder already started by its appender
            given(delegate.isStarted()).willReturn(true);
            var encoder = new MaskingEncoder(delegate);

            // when
            encoder.start();

            // then
            verify(delegate, never()).start();
            assertThat(encoder.isStarted()).isTrue();
        }
    }

    @Nested
    @DisplayName("Delegate lifecycle")
    class DelegateLifecycle {

        @Test
        @DisplayName("Propagates context so PatternLayoutEncoder can start")
        void whenContextSet_shouldPassItToDelegate() {
            // given
            var encoder = new MaskingEncoder(delegate);

            // when
            encoder.setContext(context);

            // then
            verify(delegate).setContext(context);
        }

        @Test
        @DisplayName("Propagates stop so the delegate can write its footer")
        void whenStopped_shouldStopDelegate() {
            // given
            var encoder = new MaskingEncoder(delegate);

            // when
            encoder.stop();

            // then
            verify(delegate).stop();
            assertThat(encoder.isStarted()).isFalse();
        }
    }

    private PatternLayoutEncoder startedPatternEncoder(Charset charset) {
        var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(charset);
        encoder.start();
        return encoder;
    }

    private ILoggingEvent event(String message) {
        var logger = context.getLogger("com.example.Sample");
        var loggingEvent = new LoggingEvent(MaskingEncoderTest.class.getName(), logger, Level.INFO, message, null, null);
        loggingEvent.setThreadName("http-nio-8080-exec-3");
        return loggingEvent;
    }
}

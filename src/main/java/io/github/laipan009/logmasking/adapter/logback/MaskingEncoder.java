package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

import java.nio.charset.Charset;

/**
 * Wraps a delegate encoder and masks its rendered output, including the message, stack trace and MDC
 * for supported appenders.
 * <p>The event is usually masked at the source already ({@code MaskingTurboFilter}); the wrapper is the
 * second line. It catches what the encoder adds itself, such as Logstash marker fields, and events that
 * bypassed the filter. Scanning already masked text leaves the line unchanged and returns the original
 * bytes.
 * <p>Passes the event arguments to the masker so {@code MaskedField} values, already masked by
 * {@code LogMasker}, are not masked again. An event masked at the source exposes no arguments; it hands
 * them only to this wrapper.
 * <p>Uses the delegate's charset when available from {@code LayoutWrappingEncoder} or
 * {@code LogstashEncoder}; using a different charset could corrupt non-ASCII text in the masked line.
 */
public final class MaskingEncoder extends EncoderBase<ILoggingEvent> {

    private final Encoder<ILoggingEvent> delegate;
    private final Charset charset;

    public MaskingEncoder(Encoder<ILoggingEvent> delegate) {
        this.delegate = delegate;
        this.charset = resolveCharset(delegate);
    }

    public Encoder<ILoggingEvent> delegate() {
        return delegate;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        var raw = delegate.encode(event);
        if (raw == null || raw.length == 0) {
            return raw;
        }
        var rendered = new String(raw, charset);
        var arguments = event instanceof SourceMaskedEvent atSource
                ? atSource.originalArguments() : event.getArgumentArray();
        var masked = MaskingLogbackSupport.maskLine(rendered, arguments);
        return masked == rendered ? raw : masked.getBytes(charset);
    }

    @Override
    public byte[] headerBytes() {
        return delegate.headerBytes();
    }

    @Override
    public byte[] footerBytes() {
        return delegate.footerBytes();
    }

    @Override
    public void setContext(ch.qos.logback.core.Context context) {
        super.setContext(context);
        delegate.setContext(context);
    }

    @Override
    public void start() {
        if (!delegate.isStarted()) {
            delegate.start();
        }
        super.start();
    }

    @Override
    public void stop() {
        delegate.stop();
        super.stop();
    }

    private static Charset resolveCharset(Encoder<ILoggingEvent> delegate) {
        if (delegate instanceof LayoutWrappingEncoder<ILoggingEvent> wrapping && wrapping.getCharset() != null) {
            return wrapping.getCharset();
        }
        // Logstash JSON encoders declare the charset as a string and write UTF-8 regardless of file.encoding.
        try {
            if (delegate.getClass().getMethod("getEncoding").invoke(delegate) instanceof String encoding) {
                return Charset.forName(encoding);
            }
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            // No charset declared or not recognized: use the JVM default.
        }
        return Charset.defaultCharset();
    }
}

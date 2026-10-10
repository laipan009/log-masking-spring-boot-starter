package io.github.laipan009.logmasking.adapter.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

import java.nio.charset.Charset;

/**
 * Wraps a delegate encoder and masks its rendered output, including the message, stack trace and MDC
 * for supported appenders.
 * <p>Passes the event arguments to the masker so {@code MaskedField} values, already masked by
 * {@code LogMasker}, are not masked again.
 * <p>Uses the delegate's charset when available from {@code LayoutWrappingEncoder}; using a different
 * charset could corrupt non-ASCII output.
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
        var masked = MaskingLogbackSupport.maskLine(rendered, event.getArgumentArray());
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
        return Charset.defaultCharset();
    }
}

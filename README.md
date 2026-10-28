# log-masking-spring-boot-starter

English · [Русский](README.ru.md)

[![CI](https://github.com/laipan009/log-masking-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/laipan009/log-masking-spring-boot-starter/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)

**Mask named personal data and secrets in Spring Boot / Logback logs.** Configure rules through
YAML, `@Mask`, custom policies or the explicit `LogMasker` API.

```java
log.info("email=user@example.com, status=OK");
// email=***, status=OK
```

> **Experimental: preparing the first public alpha.** The author has not validated this starter
> in production. APIs and default coverage may change. Masking does not guarantee that logs are
> free of sensitive data; validate your own logging scenarios and read the limitations below.

## Installation

Requires JDK 21. The included Maven Wrapper downloads Maven 3.9; a local Maven 3.6.3+ also works.
The current build uses Spring Boot 3.5.16; compatibility with other versions has not been
independently verified.

The artifact is not yet available from Maven Central. Build and install it locally:

```sh
./mvnw clean verify
./mvnw install -DskipTests
```

```xml
<dependency>
    <groupId>io.github.laipan009.logmasking</groupId>
    <artifactId>log-masking-spring-boot-starter</artifactId>
    <version>0.3.2-SNAPSHOT</version>
</dependency>
```

Defaults cover specific names such as `fullName`, `email`, `phone`, `passportNumber`,
`password` and `accessToken`, plus secret headers. Generic names such as `comment`, `amount`,
identifiers and business-specific fields require explicit configuration.

## How it works

The starter hooks into Logback in two places, so application code does not change and all output is
covered: library messages, stack traces and MDC. See [CHANGELOG](CHANGELOG.md) for changes between
versions.

**At the source.** A Logback turbo filter runs before the event is created, on every path: SLF4J
calls, the fluent API, and the JCL, JUL and Log4j bridges. The filter builds the event already masked
and hands it to the appenders; the original event is never created. Every consumer that reads events
past the encoder therefore receives masked data: appenders without an encoder (`SocketAppender`,
Sentry) and Java agents (OpenTelemetry reads the event on entry to `callAppenders`, before any
appender). The message, MDC, fluent API key-value pairs and exception messages across the whole
`cause` and `suppressed` chain are masked. The event exposes no arguments, because they are unmasked.
Consumers that extract the `Throwable` itself (Sentry, OpenTelemetry) receive a copy with the same
masks; an exception with nothing to mask stays original.

**In the encoder.** A wrapper around each appender's own encoder masks the rendered line, catching
what the encoder adds to the event itself. It works with plain-text and structured encoders alike; a
JSON body in the message is already masked at the source, before a JSON encoder escapes its quotes.

```text
DefaultMaskingPolicy ----+
PropertiesMaskingPolicy -+
MaskFieldIndex (@Mask) --+--> MaskingPolicyRegistry --> LineMasker (source, encoder) / LogMasker
Custom MaskingPolicy ---+

Application / libraries / JCL, JUL, Log4j --> SLF4J --> Logback
    --> MaskingTurboFilter (builds the event already masked) --> callAppenders
        --> Java agents (OpenTelemetry)
        --> appenders without an encoder, Sentry
        --> appender --> MaskingEncoder (delegate rendering + LineMasker) --> console / file / Logstash
```

Masking stays attached when Logback is rebuilt: after a Spring Cloud refresh, a `scan="true"` reload
and, for appenders added by code, within `recheck-interval`.

For startup order, scanner internals, the explicit path and design decisions, see
[Architecture](docs/architecture.md).

Policies combine names from four sources:

| Source | Location | Purpose |
|---|---|---|
| Defaults | `DefaultMaskingPolicy` | Generic sensitive names and secret headers; disable with `use-default-fields: false` |
| Annotations | `@Mask` in `io.github.laipan009.logmasking.core` | Index annotated field names and strategies |
| Configuration | `logging.masking.*` | Add names and strategies, including fields in external DTOs |
| Application beans | `MaskingPolicy` | Computed names, contextual rules or rules shared by a companion library |

Use configuration for fixed lists. A custom policy bean is useful when names are computed,
when JSON masking depends on neighboring fields, or when several applications share rules.

## Matching and replacement

Matching is exact and case-insensitive. See `DefaultMaskingPolicy` for the complete default
list. The scanner recognizes these forms:

| Form | Input | Output |
|---|---|---|
| Plain pair or query | `fullName=John Smith, attempt=3`, `?snils=1234&page=0` | `fullName=***, attempt=3`, `?snils=***&page=0` |
| JSON | `{"passportNumber":300000,"token":true}` | `{"passportNumber":"***","token":"***"}` |
| Nested or quoted value | `address={firstName=John}`, `Snils='123'` | `address=***`, `Snils='***'` |
| Secret header | `Headers: [Authorization=Bearer example]` | `Headers: [Authorization=***]` |
| Colon-separated header | `Authorization: Bearer example` | `Authorization: ***` |

Replacement values are strings, so JSON numbers and booleans become `"***"` under full masking.
Plain-text boundaries include commas, closing brackets, ampersands and line breaks. Spaces are
part of a value: `fullName=John Smith` is masked entirely. Inside dump brackets a comma ends the
value only when the next field follows: `Dto(address=Main St, Springfield, id=7)` becomes
`Dto(address=***, id=7)`. In message prose outside brackets a comma always ends the value, so the
diagnostics after it stay visible: `fullName=Smith, John` becomes `fullName=***, John`. A bracket
after a space, as in `Not found (snils=..., reason: ...)`, is not a dump. Matched brackets inside a
value are traversed whole, including a nested `AddressDto(...)`. An unmatched `(` such as `:(` does
not extend the value past the next field or the dump bracket, but an unmatched `)` ends it, leaving
the rest visible: `Dto(password=1) no address, 2) ...` becomes `Dto(password=***) no address, 2) ...`,
because the line does not show whose bracket it is. Quoted JSON values have additional boundary
handling.

Bare `Name: value` syntax is recognized **only for secret headers**. Ordinary labels often
introduce prose with no reliable end; masking them could consume the rest of the message.

### Unnamed values and null

The encoder cannot identify an unnamed value. Use explicit masking when logging a getter:

```java
log.info("request from {}", dto.getFullName()); // Unnamed value remains visible.
log.info("request from {}", logMasker.field(dto, Dto::getFullName));
// request from fullName=***

log.info("request {}", dto); // Named toString fields follow the configured policy.
```

`null` remains unchanged, for example `snils=null`.

## Strategies

| Strategy | Example | Purpose |
|---|---|---|
| `FULL` (default) | `***` | Hide the entire value |
| `LAST4` | `***8900` | Keep four Unicode code points; values of four or fewer are fully masked |
| `HASH` | `#a1b2c3d4` | First eight hexadecimal characters of SHA-256 for approximate correlation |

`field-strategies` also adds names to the masked set; do not duplicate them in
`additional-masked-fields`. Explicit strategies can override the implicit full masking of a
default name. Conflicts between explicit strategies resolve as `FULL > HASH > LAST4`,
independently of bean order. Secret headers always use full masking.

`HASH` is not cryptographic protection: predictable values can be guessed by enumeration,
and the short hash can collide. `LAST4` intentionally exposes part of the original value.

A value that already has the `HASH` form (`#` followed by eight lowercase hexadecimal characters)
is not hashed again, so bodies from `maskJson` keep the same hash as other log lines. A raw value of
exactly that form in a `HASH` field is therefore logged as is. More generally, a value that already
has the form its strategy produces is left unchanged: a message is scanned at the source and again in
the encoder, and an unchanged line keeps its original bytes.

## Configuration

```yaml
logging:
  masking:
    enabled: true
    mode: encoder                  # encoder (source and encoder masking) or off
    use-default-fields: true
    additional-masked-fields: [internalNote, requesterAlias]
    additional-secret-headers: [X-Partner-Key]
    field-strategies:
      hash: [fullName, snils]
      last4: [contractNumber]
    scan-packages: [com.example.app] # Empty: application packages
    max-body-size: 64KB             # Limit for logMasker.maskJson(...)
    recheck-interval: 10s           # 0 disables the periodic recheck
```

| Property under `logging.masking` | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Global switch; read at startup, a refresh does not change it |
| `mode` | `encoder` | `encoder` or `off`; invalid values fail context startup |
| `use-default-fields` | `true` | Include default names and secret headers |
| `additional-masked-fields` | Empty | Additional names to mask |
| `additional-secret-headers` | Empty | Additional headers, always fully masked |
| `field-strategies` | Empty | Name groups under `hash` or `last4` |
| `scan-packages` | Application packages | Packages to scan for `@Mask` |
| `max-body-size` | `64KB` | Omit oversized bodies passed to `maskJson` |
| `recheck-interval` | `10s` | How often to recheck the source filter and encoder wrappers; `0` disables it, otherwise at least `1s` |

## `@Mask`

```java
import io.github.laipan009.logmasking.core.Mask;
import io.github.laipan009.logmasking.core.MaskScope;
import io.github.laipan009.logmasking.core.MaskStrategy;

public record CustomerDto(
        @Mask String requesterAlias,
        @Mask(strategy = MaskStrategy.LAST4) String cardNumber,
        @Mask(scope = MaskScope.TYPED) String comment,
        String position) {}
```

The default scope, `ALL`, indexes the name globally, including occurrences in unrelated log
messages. `TYPED` excludes the name from the global index and applies only through
`logMasker.field(dto, CustomerDto::comment)`.

Annotations are read from fields, getters and record components. `@JsonProperty` and
`@JsonNaming` contribute both Java and JSON spellings to the index.

## `LogMasker`

Inject `io.github.laipan009.logmasking.core.LogMasker` for explicit masking:

```java
log.info("request {}", logMasker.field(dto, Dto::getFullName));
log.info("request {} {}", logMasker.field(dto, Dto::getFullName),
        logMasker.field(dto, Dto::getSnils));
log.info("body {}", logMasker.field("snils", rawSnils));
log.info("response body: {}", logMasker.maskJson(rawJson));
```

`field(dto, Dto::getFullName)` accepts an unbound getter or record accessor reference, not a
lambda body or bound reference. Names respect Jackson annotations and naming strategies.
A member's `@Mask` strategy takes precedence over the global field policy.

Unknown names are fully masked with one warning per call site. `null` is preserved. Cast a
literal null to `Object` in `field("fullName", (Object) null)` to disambiguate overloads.
The returned object computes its mask in `toString()`, deferring work until log formatting.

Pass the result as a placeholder argument, as above. The encoder recognizes it and keeps the rendered
pair: it is not masked a second time, its strategy is not replaced by the global one, and the message
text after the placeholder stays visible. Concatenated into the message string, the result is plain
text again and the plain-text boundaries apply.

`maskJson` accepts raw JSON, including output intended for destinations outside SLF4J.
Oversized or malformed bodies become `body omitted`. Contextual policies through
`MaskingPolicy.contextualMaskedFields` apply only to this JSON path because they need sibling fields.

## Performance

Run `./mvnw test -Pperf` for local measurements. These are excluded from the regular build and
have no machine-dependent pass/fail thresholds. Results depend on JVM, hardware and log format;
they are not a production SLA.

Masking at the source scans the message once more before the encoder. `MaskingSourcePerformanceTest`
measures the whole logging call, from `log.info` to bytes in the stream, without masking, with encoder
masking only and with both layers. An exception with personal data is cheaper with both layers than
with the encoder alone: its stack trace reaches the encoder already masked, so the line is not
rebuilt. Cost grows linearly with line length, so large bodies are the most expensive.

## Limitations

See [known gaps](docs/known-gaps.md) for the full list with status.

- Both layers apply to any appender with an encoder: console, files, network and message broker
  appenders, including those nested in composite appenders. Appenders without an encoder
  (`SocketAppender`, `SyslogAppender`, `SMTPAppender`, `SiftingAppender`) receive the masked event, but
  what they add to it themselves is not masked; the starter reports them at startup with INFO
  `Log masking reaches ... only through events masked at the source`. If the filter cannot be
  attached, only the encoder masks, and startup shows WARN `Log masking could not be attached to the
  event source`.
- Events carry no arguments, and `getMessage()` returns the formatted text instead of the pattern.
  Logstash `StructuredArguments` do not appear as separate JSON fields (they remain in the message
  text), and Sentry groups events by text rather than by pattern.
- An exception that had to be masked reaches Sentry and OpenTelemetry as a copy of type
  `MaskedThrowable` (`exception.type` in OpenTelemetry); the real class is in the first stack trace
  line. Exceptions with nothing to mask keep their real type.
- The SLF4J fluent API (`log.atInfo()...`) passes through turbo filters from Logback 1.5.25. With
  older Logback, and for events built outside the logger (a direct `callAppenders` or `doAppend`),
  only the encoder masks.
- A call without a message, such as `log.error(e.getMessage())` for an exception without a message,
  looks to the filter like an `isErrorEnabled()` check, so Logback builds that event itself: appenders
  without an encoder and agents receive its MDC unmasked. The encoder masks it.
- The event determines the caller (`%class`, `%method`, `%line`, OpenTelemetry `code.*` attributes)
  itself, because the class a `LocationAwareLogger` wrapper passes to Logback does not reach the
  filter. The class that called `Logger.log` is the boundary; if the wrapper passes a different one,
  add its package to `<frameworkPackage>` in logback.xml.
- Output before Logback initialization is not masked. Between initialization and application
  context readiness, only generic defaults apply; custom properties, beans and annotations
  are not yet available to the fallback.
- A Spring Cloud refresh with bootstrap (`/actuator/refresh`, Spring Cloud Bus) rebuilds Logback
  when any `PropertySourceLocator` returns something and the temporary refresh environment contains a
  `logging.*` key: from the config server response, `bootstrap.yml`, `LOGGING_*`, `-Dlogging.*`,
  `--logging.*` or `defaultProperties`. Keys from `application.yml` do not count, because the
  temporary context does not read it. The starter's own keys are `logging.*` too: if
  `logging.masking.*` comes from a config server, Logback is rebuilt on every refresh. Masking is
  reattached on `EnvironmentChangeEvent`, logged as a repeated `Log masking is attached...` line just
  before `Refresh keys changed`. If Logback is configured without XML, lines from other threads in the
  milliseconds between the rebuild and reattachment are not masked; XML configuration has no such
  window (see below).
- `enabled` and `mode` are read once at startup. A refresh restores the startup decision and does not
  revisit it: enabling or disabling masking requires a restart.
- A reloaded XML configuration (`scan="true"`, the fallback configuration after a parse error,
  `logback-spring.xml` on refresh) is wrapped immediately on the configuration thread: the starter
  listens for Logback's configuration-ended event and logs `Log masking is attached ... after Logback
  was reconfigured`. During a refresh Spring silences logging while it rebuilds Logback, so the line is
  the same as without XML, just before `Refresh keys changed`. Appenders added by code are covered by
  the periodic recheck every `recheck-interval`. Until then their lines are not masked, after which
  the log shows WARN `Log masking re-attached...`. If the pass fails, one WARN
  `Log masking re-attach failed...` is logged. A pass without changes costs tens of microseconds for
  hundreds of loggers.
- Structured encoders (Logstash, `logging.structured.format`) are supported: a JSON body in the
  message is masked at the source before escaping, and the encoder scanner works inside the message
  string without moving a mask past its closing quote. JSON that was escaped before reaching SLF4J,
  such as a JSON string inside a JSON body, can remain unrecognized.
- A masking failure at the source does not fail the request: the message text is replaced with
  `[message suppressed: log masking failed]`, and one WARN `Log masking failed...` is logged. A failure
  in the encoder still passes the line through unchanged and silently, which preserves logging but can
  expose sensitive values.
- Matching is by configured name, not by detecting arbitrary personal data or secret formats.

## Contributing, maintenance and license

See [CONTRIBUTING.md](CONTRIBUTING.md), [Architecture](docs/architecture.md), the
[roadmap](docs/roadmap.md), [known gaps](docs/known-gaps.md), the [changelog](CHANGELOG.md),
[release and internal-adoption guide](docs/releasing.md),
[GitHub setup](docs/github-setup.md) and the [security policy](SECURITY.md).

Licensed under the [MIT License](LICENSE).

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
    <version>0.3.0-SNAPSHOT</version>
</dependency>
```

Defaults cover specific names such as `fullName`, `email`, `phone`, `passportNumber`,
`password` and `accessToken`, plus secret headers. Generic names such as `comment`, `amount`,
identifiers and business-specific fields require explicit configuration.

## How it works

The starter wraps the encoders of supported Logback appenders, masking the rendered line
before bytes are written. This includes library messages, stack traces and MDC. The wrapper
can work with plain-text and structured encoders; see the structured-output limitation below.

```text
DefaultMaskingPolicy ----+
PropertiesMaskingPolicy -+
MaskFieldIndex (@Mask) --+--> MaskingPolicyRegistry --> LineMasker / LogMasker
Custom MaskingPolicy ---+

Application / libraries --> SLF4J --> Logback --> supported appender
    --> MaskingEncoder (delegate rendering + LineMasker) --> console / file
```

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
part of a value: `fullName=John Smith` is masked entirely, but `fullName=Smith, John` becomes
`fullName=***, John`. Quoted JSON values and nested values have additional boundary handling.

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
exactly that form in a `HASH` field is therefore logged as is.

## Configuration

```yaml
logging:
  masking:
    enabled: true
    mode: encoder                  # encoder or off
    use-default-fields: true
    additional-masked-fields: [internalNote, requesterAlias]
    additional-secret-headers: [X-Partner-Key]
    field-strategies:
      hash: [fullName, snils]
      last4: [contractNumber]
    scan-packages: [com.example.app] # Empty: application packages
    max-body-size: 64KB             # Limit for logMasker.maskJson(...)
```

| Property under `logging.masking` | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Global switch |
| `mode` | `encoder` | `encoder` or `off`; invalid values fail context startup |
| `use-default-fields` | `true` | Include default names and secret headers |
| `additional-masked-fields` | Empty | Additional names to mask |
| `additional-secret-headers` | Empty | Additional headers, always fully masked |
| `field-strategies` | Empty | Name groups under `hash` or `last4` |
| `scan-packages` | Application packages | Packages to scan for `@Mask` |
| `max-body-size` | `64KB` | Omit oversized bodies passed to `maskJson` |

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

## Limitations

- Appenders without supported encoders, including `SocketAppender`, `SyslogAppender` and
  `SMTPAppender`, are not covered. The starter reports them at startup.
- Output before Logback initialization is not masked. Between initialization and application
  context readiness, only generic defaults apply; custom properties, beans and annotations
  are not yet available to the fallback.
- Structured messages are scanned inside their enclosing JSON string. JSON escaped inside
  another JSON string can remain unrecognized because its keys contain escaped quotes.
- An internal masking failure passes through the original line unchanged and without a
  recursive log message. This preserves logging but can expose sensitive values.
- Matching is by configured name, not by detecting arbitrary personal data or secret formats.

## Contributing, maintenance and license

See [CONTRIBUTING.md](CONTRIBUTING.md), [Architecture](docs/architecture.md), the
[roadmap](docs/roadmap.md), [release and internal-adoption guide](docs/releasing.md),
[GitHub setup](docs/github-setup.md) and the [security policy](SECURITY.md).

Licensed under the [MIT License](LICENSE).

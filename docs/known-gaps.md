# Known masking gaps

Cases where personal data reaches the log unmasked while masking is enabled. The list exists so gaps
can be closed one at a time: take an item, close it, update its status here and add a line to the
[CHANGELOG](../CHANGELOG.md). New features belong in the [roadmap](roadmap.md); this page lists gaps
only.

How each gap is known:
- **verified**: reproduced by running a line through `LineMasker`, or through `MaskingEncoder` over a
  real `LogstashEncoder`, before masking at the source was added;
- **from code**: follows from how the starter works, not reproduced by a run;
- **hypothesis**: depends on the environment and has to be checked in a deployment.

| # | Gap | Known from | Priority | Status |
|---|---|---|---|---|
| G1 | JSON body inside a JSON log line is unmasked | verified | high | closed at the source |
| G2 | Field name not in the list: prefixes, new DTO fields | verified | high | open → F2, F3 |
| G3 | `last_name`, `last-name` do not match `lastName` | verified | medium | open |
| G4 | Application not on Logback: the starter silently does nothing | from code | medium | open |
| G5 | Failure inside the masker: the line goes out unmasked, silently | from code | medium | source closed, encoder open |
| G6 | MDC value through `%X{...}` in a pattern | verified | medium | closed at the source |
| G7 | Output bypassing Logback: stdout/stderr, uncaught exceptions | from code | medium | open |
| G8 | Java agents that ship logs themselves (OpenTelemetry, Elastic APM) | verified with the OpenTelemetry agent | high | closed at the source; check your deployment |
| G9 | Nested name: `"passport": {"number": ...}` | verified | low | open |
| G10 | Formats without `=` or JSON: `name: value`, XML, URL-encoded, non-Latin names | verified | low | on demand |
| G11 | Value without a name: text, exceptions, Hibernate bind, `value()` | verified | — | limit of the approach |
| G12 | Access logs: Tomcat, ingress, proxies | from code | low | outside the starter |
| G13 | Logback older than 1.5.18: after an XML reload, a gap until `recheck-interval` | from code | low | open |
| G14 | Windows: startup before context readiness, refresh without XML | from code | low | documented |
| G15 | Masking disabled in an environment whose logs reach shared storage | operations | medium | check your deployment |
| G16 | Events bypassing the turbo filter: fluent API on Logback older than 1.5.25, direct `callAppenders`, `log.error(null)` | from code | low | open |

---

## G1. JSON body inside a JSON log line

**How it leaks.** A JSON encoder (`LogstashEncoder`, `logging.structured.format`) escapes the quotes
inside the `message` field, so the encoder scanner sees no names in it:

```text
log.info("<--- HTTP/1.1 200 Body: {}", "{\"snils\":\"12345678900\",\"lastName\":\"Smith\"}");
→ {"message":"<--- HTTP/1.1 200 Body: {\"snils\":\"12345678900\",\"lastName\":\"Smith\"}", ...}
```

This is how a Feign logger at level `FULL`, RestTemplate and WebClient interceptors and any
`log.info("{}", objectMapper.writeValueAsString(dto))` write bodies. A toString dump in the same
`message` is masked, and so is the stack trace; the JSON is what stays open.

**Why it matters.** Log storage pipelines are usually fed with JSON, and this is the main path for
request and response bodies.

**Closed at the source.** The message is masked before the encoder, while its quotes are not yet
escaped, so the example above gives `\"snils\":\"***\"` without scanner changes. JSON that was escaped
before reaching SLF4J, such as a JSON string inside a JSON body, remains unmasked. Teaching the encoder
scanner `\"name\":` keys would close that too; the hot path must not get slower (`./mvnw test -Pperf`).

## G2. Field name not in the list

**How it leaks.** Name matching is exact and case-insensitive. Anything not in the list is open:

```text
CustomerDto(customerSnils=12345678900, taxId=7701234567)   — open without additional-masked-fields
```

Applications usually need many names in `additional-masked-fields`. A new DTO field is open until
someone adds it by hand, and the starter's own tests cannot catch that.

**What to do.** Not substring matching: `inn` inside `beginning` or `cash` inside `cashback` add noise
and hide what people debug with. Close it through process and the roadmap tools:
- F2, a startup advisor for DTO fields that look like personal data but are not in the list;
- F3, canaries: an application test logs typical DTOs and checks that no personal data reaches the
  output.

## G3. snake_case and kebab-case

**How it leaks.**

```text
CustomerDto(last_name=Smith, snils=12345678900)   → last_name=Smith, snils=***
GET /customers?last-name=Smith&id=7               → open
```

**What to do.** Compare names without `_` and `-`: in the registry when it is built, and in the line
during lookup, without allocations (character-by-character comparison that skips separators). The
default list is already lowercase without separators. Check `./mvnw test -Pperf`.

## G4. Application not on Logback

**How it leaks.** `MaskingLogbackApplicationListener` and `MaskingWatchdog` exit silently if
`LoggerFactory.getILoggerFactory()` is not Logback, for example with `spring-boot-starter-log4j2`.
Nothing says that masking is inactive.

**What to do.** A startup WARN such as `Log masking is NOT active: logging is not Logback (...)` with
the factory class name. The adapter itself is F4 in the roadmap.

## G5. Failure inside the masker

**How it leaks.** `MaskingLogbackSupport.maskLine` catches the exception and returns the line as is, on
purpose, so the request does not fail and logging does not recurse. A scanner bug on some line shape
would expose every such line, and nobody would know.

**The source is closed.** A failure while masking an event replaces the text with
`[message suppressed: log masking failed]` and logs one WARN. A line stays open only if the encoder
scanner fails on what the encoder added itself.

**What to do for the encoder.** As in `MaskingWatchdog`: one WARN with the exception per process. Do
not log from `encode()` (appender re-entry is dropped and the lock is held); set a flag that the
periodic pass or the first call outside the encoder reports.

## G6. MDC through `%X{...}`

**How it leaked.** A pattern such as `[%X{customerName}]` prints only the value, with no name in the
line:

```text
[John Smith] INFO Service - request processed   — open
```

A JSON encoder prints MDC with its key and masks it.

**Closed at the source.** An MDC value whose key is in the registry is masked with the key's
strategy, and other values are scanned as a line: `[%X{snils}]` gives `[***]`. A key that is not in
the list (`customerName`, unless added to `additional-masked-fields`) stays open, like any unlisted
field (G2).

## G7. Output bypassing Logback

**How it leaks.** These do not pass through Logback:
- an uncaught exception on a thread outside Spring: the default handler prints the stack trace with
  its message to stderr;
- `e.printStackTrace()`, `System.out.println`;
- `spring.jpa.show-sql`.

**What to do.** The starter could install `Thread.setDefaultUncaughtExceptionHandler` that writes
through SLF4J, only when no handler is set. Replacing `System.out` goes too deep. The rest is code
review.

## G8. Java agents that ship logs themselves

**How it leaked.** The OpenTelemetry agent with `otel.logs.exporter=otlp`, and Elastic APM with
`log_sending` or `log_ecs_reformatting`, take the Logback event before the encoder and send it
themselves. The agent may be added by a base image startup script rather than by the application.

**Reproduced and closed at the source.** OpenTelemetry agent 2.33.0 with
`otel.logs.exporter=console`, with encoder-only masking: stdout was masked while the agent's record
was not (body, MDC, `exception.message`, `exception.stacktrace`). The agent reads the event on entry
to `Logger.callAppenders`, and that event is now masked; an exception with personal data reaches the
agent as a copy whose `exception.type` is `MaskedThrowable`.

**Check your deployment.** JVM arguments (`-javaagent`, `JAVA_TOOL_OPTIONS`), `OTEL_*` and
`ELASTIC_APM_*` variables, and whether logs reach storage through the agent. A startup WARN based on
`-javaagent` in `RuntimeMXBean.getInputArguments()` would make this visible.

## G9. Nested name

**How it leaks.**

```text
{"passport": {"number": "4510 123456"}}   — open: the list has passportNumber, not number
```

**What to do.** Verify that an object value is masked entirely when the parent name is in the list,
including multi-line JSON. If so, consider adding `passport` and similar names to the defaults.

## G10. Formats without `=` or JSON

**How it leaks.** Open:

```text
snils: 12345678900, lastName: Smith
<customer><snils>12345678900</snils></customer>
body=snils%3D12345678900%26lastName%3D...
```

Field names in a non-Latin script are open too unless they are configured explicitly.

**What to do.** On demand: XML if SOAP integrations appear, `name: value` if a real source of such
output is found, non-Latin names as an explicit list.

## G11. Value without a name

Message text, an exception message (`Customer with SNILS 12345678900 not found`),
`binding parameter (1:VARCHAR) <- [12345678900]` from Hibernate, `StructuredArguments.value()`.

**Limit of the approach.** Regular expressions over values were rejected: up to ten times the cost of
the hot path. Use `LogMasker.field()` in code, code review and canaries (F3).

## G12. Access logs

Tomcat's `AccessLogValve` writes on its own, and ingress controllers and proxies run outside the JVM.
A query string with personal data (`?snils=...`) reaches them. This is outside the starter; a WARN when
`server.tomcat.accesslog.enabled=true` is possible.

## G13. Logback older than 1.5.18

There are no configuration events: after an XML reload (`scan`, a refresh with `logback-spring.xml`)
masking returns only with the periodic pass, within `recheck-interval`. The subscription fails silently.

**What to do.** A startup INFO saying that events are unavailable and only the periodic pass remains.

## G14. Windows

Documented in the README:
- from Logback initialization until the context is ready, only the built-in list applies; names from
  `additional-masked-fields` and `@Mask` are not masked yet (`@PostConstruct`, cache warm-up);
- a refresh without XML configuration: lines from other threads in the milliseconds between the
  rebuild and reinstallation.

## G15. Masking disabled in an environment

An environment with `logging.masking.enabled: false` exposes its data if its logs reach shared
storage and it holds real data. Check where such environments send logs and what data they hold.

## G16. Events bypassing the turbo filter

The SLF4J fluent API (`log.atInfo()...`) passes through turbo filters only from Logback 1.5.25; with
older Logback, appenders without an encoder and agents receive such events unmasked. The same applies
to events built outside the logger: a direct `callAppenders` or `doAppend`, receivers of remote
Logback events. The encoder masks them.

A line without a message (`log.error(null)`, most often `log.error(e.getMessage())` for an exception
without a message) reaches the filter exactly like an `isErrorEnabled()` check: without text,
arguments or exception. The filter cannot tell them apart, so Logback builds the event, and appenders
without an encoder and agents receive its MDC unmasked. Such an event has no text, and MDC in encoder
output is masked.

**What to do.** A startup INFO when Logback is older than 1.5.25.

---

## Deployment checklist

- [ ] JVM arguments: is there a `-javaagent` (G8)? Check `ps -ef | grep java` and `JAVA_TOOL_OPTIONS`.
- [ ] Startup shows INFO `Log masking is attached to ... and to the event source` and no WARN
      `could not be attached to the event source`.
- [ ] An external Logback configuration (from a base image script or a config server): which
      appenders write without an encoder (INFO `Log masking reaches ... only through events masked at
      the source`)?
- [ ] A record with personal data in log storage after rollout shows `"snils":"***"` and the stack
      trace is intact.
- [ ] Where do environments with masking disabled send logs, and do they hold real data (G15)?

## Closed

- Masking was lost after a Spring Cloud refresh and after a `scan="true"` reload.
- `LogstashTcpSocketAppender` and `KafkaAppender` were not masked.
- A value with a comma or an unmatched bracket inside a toString dump was exposed.
- After a refresh with `logback-spring.xml` nothing reported that masking was back.
- JSON body inside a JSON log line (G1), MDC through `%X{...}` (G6), agents and appenders without an
  encoder (G8): closed by masking at the source.

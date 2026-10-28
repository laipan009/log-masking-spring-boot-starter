# Changelog

## Unreleased — 0.3.2-alpha.1

Initial public preparation. Experimental; not yet validated in production.

- Generic masking defaults, configurable policies and annotation-based field discovery.
- Logback encoder integration and explicit `LogMasker` API.
- Neutral package and artifact names, public build configuration and CI.
- Unit and integration tests using synthetic sample scenarios.

- English documentation, source comments, test descriptions and diagnostic messages.
- MIT license and owner review configuration.
- Russian README, architecture guide (English and Russian) and security policy.
- Maven Wrapper; CI and documentation build with `./mvnw`.
- Issue templates and Dependabot configuration.
- Fixed: the encoder masked `LogMasker` output a second time. `field()` placeholders no longer
  swallow the message text after them, `HASH` values from `field()` and `maskJson()` match the
  documented SHA-256 prefix, and a member `@Mask` strategy is no longer replaced by the global one.

### Masking at the source

Log events are now masked when the logger is called, before appenders and Java agents read them. The
encoder wrapper stays as a second line. No configuration change is needed.

- Fixed: consumers that bypass the encoder received events unmasked: the OpenTelemetry Java agent
  (it reads the event on entry to `callAppenders`, before any appender), appenders without an encoder
  (`SocketAppender`, `SyslogAppender`) and Sentry. They now receive masked messages, MDC, SLF4J fluent
  API key-value pairs and exceptions, including `exception.message` and `exception.stacktrace`.
- Fixed: a JSON body inside a JSON log line was unmasked because `LogstashEncoder` escaped its quotes
  before the scanner saw them. The body is now masked before escaping: `\"snils\":\"***\"`.
- Fixed: an MDC value printed through `%X{...}` in a pattern was unmasked. A value whose key is a
  configured name is now masked with that name's strategy.
- Changed: a masking failure at the source replaces the message with
  `[message suppressed: log masking failed]` and logs one WARN. A failure in the encoder still passes
  the line through unchanged.
- Changed: events carry no argument array, and `getMessage()` returns the formatted text instead of the
  pattern. Logstash `StructuredArguments` no longer appear as separate JSON fields; they remain in the
  message text.
- Changed: an exception that had to be masked reaches Sentry and OpenTelemetry as a `MaskedThrowable`
  copy (`exception.type` in OpenTelemetry); the real class name is in the first stack trace line.
  Exceptions with nothing to mask are passed as is.
- Changed: the SLF4J fluent API (`log.atInfo()...`) is masked at the source from Logback 1.5.25; with
  older Logback only the encoder masks it.
- Changed: the event determines the caller (`%class`, `%line`, OpenTelemetry `code.*`) itself. For a
  `LocationAwareLogger` wrapper, the class that called `Logger.log` is the boundary; if the wrapper
  passes Logback a different class, add its package to `<frameworkPackage>`.
- Changed startup lines: `Log masking is attached to N appender(s) and to the event source...` (INFO).
  Appenders without an encoder are reported with INFO `Log masking reaches ... only through events
  masked at the source` instead of WARN `NOT applied`. WARN `Log masking could not be attached to the
  event source` means only encoder output is masked.
- Cost: a message is scanned twice, at the source and in the encoder. In local measurements against
  encoder-only masking, an ordinary line cost about 80 ns more, a dump with personal data about 200 ns
  and a 4 KB embedded JSON body about 3 µs; an exception with personal data became about 6 µs cheaper,
  because its stack trace reaches the encoder already masked.

### Keeping masking attached when Logback is rebuilt

- Fixed: a Spring Cloud refresh with bootstrap (`/actuator/refresh`, Spring Cloud Bus, a config
  server change) rebuilds Logback when a `logging.*` key comes from the config server, `bootstrap.yml`,
  `LOGGING_*` or `-Dlogging.*`. The new appenders had no wrappers, and all output stayed unmasked until
  a restart. Masking is now reattached right after the refresh.
- Fixed: after a logback.xml reload (`scan="true"`) masking was also lost until a restart, and appenders
  added by code after startup were never wrapped. A reloaded XML configuration is now wrapped
  immediately, added appenders within `recheck-interval`.
- Fixed: a refresh enabled masking in an application that disabled it in `application.yml`.
- Changed: `logging.masking.enabled` and `mode` are read once at startup and a refresh does not revisit
  them; enabling or disabling masking requires a restart.
- Added: `logging.masking.recheck-interval`, default `10s`: how often the `log-masking-watchdog` daemon
  thread checks that the source filter and encoder wrappers are in place. `0` disables the check;
  non-zero values below `1s` fail startup.
- New log lines: `Log masking is attached ... after Logback was reconfigured` (INFO),
  `Log masking re-attached ...` (WARN: the recheck found an unmasked appender) and
  `Log masking re-attach failed ...` (WARN, once).

### Appenders and value boundaries

- Fixed: `LogstashTcpSocketAppender` and `KafkaAppender` were not masked. Any appender with an encoder
  is now wrapped, including one outside `OutputStreamAppender` and a composite appender that also
  writes itself; `KafkaAppender` is wrapped together with its fallback appenders. The starter keeps no
  list of appender classes.
- Fixed: a value with a comma inside a toString dump was exposed after the first comma
  (`address=***, Springfield, Building 19`). Inside a dump the value now extends to the next field; in
  message prose outside brackets a comma is still a boundary.
- Fixed: an unmatched bracket in a value (`:(`, `(by proxy`) consumed the closing bracket of the dump,
  and the mask swallowed neighbouring fields. A bracket in message prose
  (`Not found (snils=..., reason: ...)`) was taken for a dump and hid the reason.
- Fixed: non-ASCII text in a masked line from a Logstash JSON encoder was corrupted when the JVM
  charset was not UTF-8.

### Dependencies

- Jackson 2.21.8 (CVE-2026-89425, CVE-2026-89407, CVE-2026-91776, CVE-2026-91777). Applications get
  Jackson from their own Spring Boot, and Boot 3.5.16 manages 2.21.4: raise `jackson-bom.version` to
  2.21.7 or later in the application as well.
- Tomcat 10.1.61 (CVE-2026-65905, CVE-2026-65182, CVE-2026-68525) and OpenTelemetry 1.62
  (CVE-2026-45292); both are used only in tests here.
- New test-only dependencies: `spring-cloud-context`, `logstash-logback-encoder`,
  `opentelemetry-logback-appender-1.0`, `opentelemetry-sdk-testing` and `sentry-logback`.

Known gaps that remain are listed in [docs/known-gaps.md](docs/known-gaps.md).

This entry is not a release announcement.

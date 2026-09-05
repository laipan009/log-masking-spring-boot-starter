# Roadmap

These are proposals, not implemented features or release commitments. Each section describes
motivation, behavior, implementation points and acceptance checks. Open a branch and pull
request for a proposal, and update its status here.

| ID | Feature | Status | Tentative milestone |
|---|---|---|---|
| F1 | Report mode: preview matching rules before masking | Proposed | Unscheduled |
| F2 | Startup advisor for potentially sensitive unmasked DTO fields | Proposed | Unscheduled |
| F3 | Consumer test artifact with log canaries | Proposed | Unscheduled |
| F4 | Log4j2 adapter | On demand | Unscheduled |

Preserve default behavior and the `LineMasker.mask` hot path. Compare `mvn test -Pperf` results
within normal measurement variance. Applications upgrading without configuration changes
should retain their existing behavior.

## F1. Report mode

### Motivation and behavior

Teams need to see which diagnostic fields would be hidden before enabling masking.
A proposed `logging.masking.mode: report` wraps encoders but returns the original bytes,
leaving values visible. Use only with suitable test data in a staging environment.
Report each matched field name and its policy source once, without printing its value.
After reviewing the report, switch to `encoder`.

### Implementation

- Add `REPORT` to `MaskingProperties.Mode`, parsing and property binding. Install wrappers
  for report mode in `MaskingLogbackApplicationListener`.
- Add `LineMasker.scan(String, Consumer<String>)` using the same scanner as `mask`, sharing
  private matching code while leaving the normal masking path unchanged.
- Add `MaskingPolicyRegistry.sourceOf(String)`. Record the contributing policy class when
  building the name table; display `MaskFieldIndex` as `@Mask`.
- Keep the mode alongside the masker in `MaskingLogbackSupport`. Report mode scans and
  returns the original line.
- Do not log synchronously from `encode()`: appender reentrancy guards can discard output,
  and output appenders hold locks. Collect names in `ConcurrentHashMap.newKeySet()` and
  report newly discovered names from one daemon scheduled task every five seconds.

### Tests and acceptance

Check that `scan` finds the same names as `mask`, makes no callbacks without matches, and
never rebuilds the line. Test all four policy sources. An integration test must show that
canary values remain visible in report mode while the expected names and sources are reported.
Test listener installation in report mode and its absence in off mode.

Update the README mode table and staging rollout instructions. Verify encoder performance
remains within measurement variance. Frequency counts, Micrometer metrics and an actuator
endpoint are separate observability work built on the scan API.

## F2. Startup advisor

### Motivation and behavior

A newly added sensitive DTO field can be missing from the policy. Reuse application package
discovery to report likely omissions once at startup, with no per-log-line overhead.
Proposed configuration: `logging.masking.advisor: warn` (default) or `off`.

Example warning:

```text
Log masking advisor: 3 field(s) look like personal data but are not masked: CustomerDto.passportSeries, CustomerDto.mobilePhone, ProfileUpdateRequest.birthDate. Add @Mask, logging.masking.additional-masked-fields, or @NotPii if the field is not personal data
```

Limit the report to 50 names, followed by an "and N more" suffix. Exclude fields whose Java
or Jackson name is already covered, secret headers, fields annotated with `@Mask` in either
scope, and fields carrying a new runtime `@NotPii` annotation on a field, getter or record
component. This advisor must not fail startup.

### Heuristic and implementation

Split camelCase and snake_case names into whole words, ignoring digits. Candidate words:
`fio, name, surname, lastname, firstname, middlename, patronymic, snils, inn, passport,
phone, mobile, email, mail, birth, birthday, address, salary, iban, card, account, login,
password, token, secret`, with optional transliterations `familiya, imya, otchestvo`.

A final word in `id, code, type, kind, template, model, count, flag, enabled, format, mask,
length, size` suppresses the match. For example, `phoneModel`, `emailTemplateId` and
`salaryGradeCode` should not trigger, while `mobilePhone` and `birthDate` should.
Document the rationale for dictionary and exclusion entries.

- Add `core/NotPii.java` and a pure
  `autoconfigure/PiiHeuristic.suspiciousWord(String): Optional<String>`.
- Add `PiiAdvisor`. The existing scanner loads only classes containing the `@Mask` descriptor,
  while this advisor needs other DTO candidates. Reuse resources but bound class loading
  through suffixes such as `Dto`, `Request`, `Response`, `Entity`, `Model`. Decide how to
  include records without loading the entire classpath. Load without initialization and
  handle linkage errors as the existing scanner does.
- Run on `ApplicationReadyEvent` when enabled and report scan duration. Reuse `MemberNames`
  naming helpers so advisor and encoder interpret names consistently.

### Tests and acceptance

Use parameterized heuristic tests for `fullName`, `passportSeries`, `mobilePhone`, `birthDate`,
`phoneModel`, `emailTemplateId`, `nameCount`, `customer_snils`, `id` and `position`.
Test suppression by existing coverage, `@Mask` and `@NotPii`, and reporting as `Class.field`.
An integration fixture with one deliberately uncovered field should report exactly that field.

Document the advisor and `@NotPii`. Evaluate false positives in the sample and consumer test
applications, recording reasons for exclusions. A future Maven plugin with `failOnSuspicious`
is outside scope until the heuristic has been exercised in several real applications.

## F3. Consumer testing artifact

### Motivation and proposed API

Consumers need regression tests for their own logs, similar to this project's `LogPiiCanaryIT`.

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ExtendWith(LogCanaryExtension.class)
class ProfileControllerIT {
    @Test
    void update_shouldNotLeakPersonalData(LogCanaries canaries) {
        var request = new ProfileUpdateRequest(
                canaries.value("fullName"), canaries.value("snils"));
        restTemplate.postForEntity("/v1/profiles", request, Void.class);
        // The extension checks stdout and stderr after the test.
    }
}
```

`canaries.value("fullName")` creates and remembers a unique marker such as
`CANARY-fullName-3f9a1c`. A leaked marker fails the test with its field name and offending line.
`canaries.expectMasked("fullName")` additionally requires a corresponding masked pair, proving
that the field was logged and masked rather than omitted entirely.
Provide `LogCanaryAssert.assertNoLeak(String, LogCanaries)` for consumers with their own capture.

### Implementation

- Introduce parent `log-masking-parent` and modules `log-masking-spring-boot-starter` and
  `log-masking-test`, sharing a version and the current groupId. Keep the starter artifactId.
- The test module depends only on `junit-jupiter-api` with provided scope, not on the starter.
- Use before/after test callbacks and tee PrintStreams to capture stdout and stderr. Avoid
  depending on Spring's `OutputCaptureExtension`, whose captured output is not available to
  another extension through a public API.
- Supply `LogCanaries` through `ParameterResolver` with per-method extension store state.
- Migrate `LogPiiCanaryIT` and `LogMaskerFieldIT` to exercise the new artifact themselves.

### Tests and acceptance

Test stdout leaks, stderr leaks, clean output, missing expected masks, and a single leak among
several canaries. Verify output streams are restored after success and exceptions.
Document consumer coordinates and examples; publish both modules in one release workflow.
Keep all existing integration scenarios.

Decide the module layout before implementation. A temporary package inside the starter with
optional JUnit dependency is an alternative, but moving it later would break consumer imports.

## F4. Log4j2 adapter

### Motivation and behavior

Automatic integration currently supports Logback. Applications using Log4j2 can use the
explicit masking API but do not receive automatic encoder masking. Implement this on demand.

Logback allows replacing an appender encoder; Log4j2 appenders receive a layout at construction
and do not offer the equivalent setter. The proposed adapter therefore requires an explicit
layout wrapper, plus startup diagnostics for uncovered appenders:

```xml
<Console name="stdout">
    <MaskingLayout>
        <PatternLayout pattern="%d %-5level [%t] %logger{36} - %msg%n"/>
    </MaskingLayout>
</Console>
```

Support `StringLayout` delegates, including `JsonTemplateLayout`. If Log4j2 is detected without
wrappers, report that masking is not applied and point to the README. Report partially uncovered
configurations too. Respect `logging.masking.enabled` and `mode=off`.

### Implementation

- Add `adapter/log4j2/MaskingLayout` as a Core layout plugin with a `StringLayout` delegate.
  Extract logger-independent support from `MaskingLogbackSupport` into `MaskingSupport`.
  Implement serialization, byte conversion and encoding consistently, preserving the fast
  path when no match exists.
- Keep `log4j-core` optional, with adapters conditional on class availability. A separate
  module is unnecessary while the adapter remains small.
- Generate `Log4j2Plugins.dat` through annotation processing instead of requiring consumers
  to configure package scanning.
- Extend startup diagnostics to inspect Log4j2 appenders and their layouts. This is a
  coverage check, not automatic layout replacement.

### Tests and acceptance

Test plain and structured delegates, sensitive values, unchanged byte arrays and listener
warnings. Add a separate Maven `log4j2` integration profile that excludes Logback, avoiding two
SLF4J providers in the same classpath. A sample `log4j2-spring.xml` must mask all canary values.

Document explicit installation. Keep the regular Logback `mvn verify` build and performance
measurements unchanged; run Log4j2 integration tests separately with `mvn verify -Plog4j2`.

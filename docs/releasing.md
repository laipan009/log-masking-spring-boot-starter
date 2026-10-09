# Public release preparation and internal adoption

## Current state

The working version is `0.3.0-SNAPSHOT`. The intended first public release is
`0.3.0-alpha.1`, marked as a GitHub prerelease. No production validation is claimed.
No artifact is currently published to Maven Central.

Before creating the initial public repository:

1. The GitHub owner is `laipan009`; the proposed repository is
   `log-masking-spring-boot-starter`. The project uses the MIT license.
   Maven Central namespace verification is a separate step if Central publication is desired.
2. Check LICENSE and POM metadata against the intended public repository.
3. Review the exact source snapshot, including hidden files. Do not copy `.git`, build output,
   local settings, private deployment files or private policy data.
4. Use `python3 scripts/export-public.py /absolute/path/to/new-repository` to export
   the committed tree into a new repository with one root commit on `main`.
   The destination must not exist. See [GitHub setup](github-setup.md).
   A branch in the original repository still includes its original history. Do not push
   the preparation branch, old tags, or all refs to the public repository.
5. Run `./mvnw clean verify` in the new repository and check GitHub CI.
6. Set the version to `0.3.0-alpha.1`, build and verify the exact release commit, tag `v0.3.0-alpha.1`,
   and create a GitHub prerelease explaining the experimental status and known limitations.
   Do not label it production-ready or stable. Begin the next iteration as a snapshot.

GitHub source publication and Maven Central publication are separate steps. Until an artifact
repository is configured, consumers build and install from source as shown in the README.

## One shared implementation

Maintain the masking engine in the public repository. Internal applications consume a pinned
version through their normal artifact repository. Keep organization-specific fields, headers,
DTOs, deployment configuration and credentials in a private companion module or application.
The public project should not need a long-lived internal fork of its engine.

Before replacing an existing internal starter, preserve its complete old default field and
header lists in a private `MaskingPolicy` bean. Compare old coverage with the union of the
new generic defaults and this private policy. Do not rely on the smaller public default set
as a drop-in replacement. Update annotation imports and any scan-package settings.

A private module can contribute a bean like this (names below are synthetic):

```java
@Bean
MaskingPolicy applicationMaskingPolicy() {
    return new MaskingPolicy() {
        @Override
        public Set<String> maskedFields() {
            return Set.of("internalReference", "accountBalance");
        }
        @Override
        public Set<String> secretHeaders() {
            return Set.of("X-Internal-Token");
        }
    };
}
```

Imports: `io.github.laipan009.logmasking.core.MaskingPolicy`, `java.util.Set`,
`org.springframework.context.annotation.Bean`. Alternatively configure
`logging.masking.additional-masked-fields` and `additional-secret-headers`.

Custom beans, properties and annotation scanning become available after context initialization.
The early Logback fallback uses only generic defaults; an internal policy does not extend that
fallback. Verify startup logs as well as normal traffic before adopting the public starter.

Validate representative application logs, exception paths, unsupported appenders, structured
output, latency and allocation cost in a staging environment. Pin versions and promote after
observing real application behavior. Feed generic fixes back to the public repository; keep
internal reproducers private and publish synthetic regression tests.

Generic defaults remain enabled unless `logging.masking.use-default-fields=false`.
Application `MaskingPolicy` beans add rules to those defaults; consumers do not need to fork
the engine. Corporate dependency approval and import are managed by the consumer organization.

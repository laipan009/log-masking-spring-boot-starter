# Changelog

## Unreleased — 0.3.0-alpha.1

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

This entry is not a release announcement.

# Security policy

This project exists to keep sensitive values out of logs, so a way to bypass masking is treated as a
security issue.

## Supported versions

The project is experimental and has no stable release yet. Only the latest commit on `main` and the
most recent prerelease, once published, receive fixes.

## Reporting a vulnerability

Report privately through GitHub: open the repository's **Security** tab and choose
**Report a vulnerability**. Do not open a public issue or pull request for a suspected bypass.

Include:

- the starter, Java, Spring Boot and Logback versions;
- the masking configuration and the logging configuration (appender and encoder);
- a minimal reproducer and the expected versus actual log line.

Use synthetic values such as `user@example.com` or `CANARY-snils-0001`. Never send real personal data,
credentials, tokens or internal hostnames, even in a private report.

The project has a single maintainer and no response-time guarantee. Confirmed issues are fixed on
`main` and disclosed through a GitHub security advisory.

## What counts

In scope:

- a configured field name, default name or secret header whose value reaches a covered appender
  unmasked in a form the README describes as supported;
- a crafted log line that makes masking consume or corrupt unrelated parts of the output;
- a failure inside masking that exposes data beyond the documented fail-open behavior.

Not vulnerabilities, but welcome as ordinary issues: names that are not configured, unnamed values,
appenders listed as unsupported, output before Logback initialization, and the other documented
[limitations](README.md#limitations).

# Contributing

The project is experimental and has not yet been validated in production.

Use JDK 21 and Maven 3.6.3 or newer. Run `mvn clean verify` before opening a pull request;
this includes unit tests and HTTP integration tests. Run `mvn test -Pperf` separately for
performance measurements. Measurements are environment-specific, not release guarantees.

For a bug report, include a minimal reproducer, Java / Spring Boot / Logback versions,
configuration and expected versus actual output. Use synthetic values such as
`user@example.com`; remove real personal data, tokens and internal addresses.

Keep business-specific policies in consumer applications or private companion libraries.
Propose generic behavior changes with tests, and describe any changes to default coverage.

CI uses the official [checkout](https://github.com/actions/checkout) and
[setup-java](https://github.com/actions/setup-java) actions. CI does not publish releases.

External contributions use fork pull requests. The maintainer reviews all changes; code-owner
approval and successful CI are required once branch protection is enabled. Workflow execution
approval is a separate decision from merge approval. See [GitHub setup](docs/github-setup.md).

Contributions are made under the project's [MIT License](LICENSE).

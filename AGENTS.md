# AGENTS.md: Ledgerline engineering conventions

This file is the single source of truth for anyone changing this repository, human or AI agent.
`CLAUDE.md` points here. If a rule below conflicts with a habit, the rule wins.

## 1. Purpose and the honesty rule

Ledgerline is an **internal engineering demonstration by SpecShield Labs**: a reliable order and payment
event processing service (outbox, webhooks, idempotency, a mock payment provider and chaos experiments).

- **Synthetic data only.** No real cardholder data, PANs, bank details or personal data, ever, including in
  tests, fixtures, logs, screenshots and issues.
- **Not a production payment system.** It is not PCI DSS scoped and must never be presented as if it were.
- **No fabricated metrics.** Every number in the README, docs, PRs or slides (throughput, latency, coverage,
  mutation score, uptime) must come from a run that anyone can reproduce from this repo. State how it was
  measured. If you have not measured it, do not write it. Badges show live CI values or stay placeholders.
- If you cannot verify something (a version, a behaviour, a benchmark), say so explicitly.

## 2. Layout

```
ledgerline-api/   Spring Boot 4 service (Java 25), Maven module
ops-console/      React operations console (added in a later step)
```

Package root `com.specshield.ledgerline`:

| Package       | Responsibility                                                        |
|---------------|-----------------------------------------------------------------------|
| `domain`      | Pure model: entities, value objects, domain events, invariants        |
| `application` | Use cases; orchestrates domain and ports; owns transactions           |
| `web`         | Controllers, DTOs, problem+json mapping                               |
| `persistence` | JPA entities, Spring Data repositories, mapping to and from domain    |
| `webhook`     | Inbound/outbound webhooks, signatures, delivery                       |
| `outbox`      | Transactional outbox and relay                                        |
| `mockpay`     | Synthetic payment provider (never a real network)                     |
| `chaos`       | Fault injection, off unless explicitly enabled                        |
| `config`      | Spring wiring: security, `Clock`, OpenAPI                             |

## 3. Architecture rules (enforced by `ArchitectureTest` where possible)

1. **`domain` has no Spring, JPA or web imports.** No `org.springframework..`, `jakarta.persistence..`,
   `..web..` or `..persistence..` dependencies. (LL-A01)
2. **Controllers never call repositories.** `web` must not depend on `persistence`; go through
   `application`. (LL-A02)
3. **Money is `long` minor units (cents) with ISO 4217 currency `"USD"`.** Never `double`/`float`, never
   `BigDecimal` for stored amounts. Amount and currency always travel together.
4. **All times are UTC `java.time.Instant`.** No `LocalDateTime`, `Date` or zone-less timestamps in the
   domain, API or database (`timestamptz`). Hibernate is configured with `jdbc.time_zone=UTC`.
5. **Inject `java.time.Clock` everywhere time matters.** Never call `Instant.now()` / `System.currentTimeMillis()`
   without a `Clock`. The production bean is `Clock.systemUTC()` (`config.ClockConfig`); tests use
   `Clock.fixed(...)` or a mutable test clock.
6. Schema changes are forward-only Flyway migrations in `src/main/resources/db/migration`
   (`V<n>__description.sql`). Hibernate only validates (`ddl-auto: validate`).

## 4. Error format

**Every error response is RFC 9457 `application/problem+json`**, including validation errors, 404s,
405s, security failures (401/403) and unexpected 500s. Use Spring's `ProblemDetail`; set a stable `type`
URI and `title` per error kind. Never leak stack traces, exception class names or SQL
(`server.error.include-stacktrace: never`).

## 5. Testing rules

- **Every behaviour change ships with tests.** No exceptions for "small" changes.
- **Integration tests use Testcontainers PostgreSQL 17 (`postgres:17-alpine`), never H2.** Use
  `@ServiceConnection`. Integration test classes end in `IT` (run by failsafe). Unit tests end in `Test`
  (run by surefire).
- **Test names follow `LL-XXX description`** (via `@DisplayName`) using IDs from the test catalogue when one
  exists for that behaviour. Do not invent catalogue IDs; if none exists, use a plain description.
- Tools available: JUnit 6 (Jupiter), AssertJ, ArchUnit, jqwik (property-based), WireMock (HTTP fakes),
  REST Assured, Testcontainers.
- Tests that depend on time use an injected `Clock`, never sleeps or wall-clock assertions.
- Coverage is gated by JaCoCo (line coverage, see `jacoco.line.minimum` in `ledgerline-api/pom.xml`).
  Raise the gate as code lands; never lower it to get a build through.
- Mutation testing (PIT) is configured but not gated yet.

## 6. Commands

Requires JDK 25 (`JAVA_HOME` must point at it) and Docker (for Testcontainers and Compose).

| Command | What it does |
|---|---|
| `./mvnw verify` | Compile, format check (Spotless), unit + integration tests, coverage report + gate, SBOM |
| `./mvnw -Pmutation test-compile pitest:mutationCoverage` | Mutation testing; report at `ledgerline-api/target/pit-reports/index.html` |
| `./mvnw spotless:apply` | Format the code (palantir-java-format) |
| `docker compose up --build` | Start PostgreSQL 17 and the app on http://localhost:8080 |

Docker notes: with Colima, export `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` before `./mvnw verify`. Compose host ports
are overridable with `LEDGERLINE_HTTP_PORT` / `LEDGERLINE_DB_PORT`.

Note: `./mvnw -Pmutation pitest` is not a valid Maven invocation (`pitest` is a plugin prefix, not a
lifecycle phase). Use the command in the table above.

## 7. Definition of Done

A change is done only when **all** of the following hold:

- [ ] Tests green locally (`./mvnw verify`) **and** in CI.
- [ ] No new warnings (compiler, build, test output, Spotless).
- [ ] OpenAPI is updated (annotations/descriptions accurate; `/v3/api-docs` reflects the change).
- [ ] The relevant README section is updated.
- [ ] No secrets committed: no tokens, keys or real credentials. Local dev credentials guard only
      throwaway containers with synthetic data.
- [ ] Honesty rule respected: any claimed number is reproducible.

## 8. Dependencies and supply chain

- Pin exact versions. Prefer versions managed by the Spring Boot BOM; pin anything else explicitly.
- GitHub Actions are pinned to full commit SHAs (with the tag in a comment). Docker base images are pinned
  by digest. Dependabot proposes updates weekly.
- Do not add a dependency without saying why in the PR.

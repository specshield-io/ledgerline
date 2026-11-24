---
status: accepted
date: 2026-11-24
decision-makers: SpecShield Labs engineering
---

# ADR 0006: Testing strategy: Testcontainers, property-based and mutation testing

## Context and Problem Statement

Ledgerline's guarantees (no double application, no lost notification, no forged event) depend on PostgreSQL
behaviour: unique constraints under concurrency, `ON CONFLICT`, `FOR UPDATE`, `SKIP LOCKED`, `timestamptz`. They also
depend on orderings no one would think to write by hand (a refund arriving before the payment it refunds). And a
high coverage number can hide tests that execute code without checking it. What tests give real confidence in
these guarantees, at a cost a small team can keep paying?

## Decision Drivers

* Tests must exercise the real database semantics the guarantees rely on.
* Event-ordering bugs hide in combinations; examples alone miss them.
* We want evidence that tests *check* behaviour, not only run it.
* Fast feedback for the domain; integration tests where integration is the point.
* Every number we publish comes from a CI report (AGENTS.md, honesty rule).

## Considered Options

* **Integration database:** Testcontainers PostgreSQL 17 · H2 in PostgreSQL mode · a shared test database
* **Ordering logic:** jqwik property-based tests · hand-written example tests only
* **Test quality:** PIT mutation testing with a gate · line coverage only

## Decision Outcome

Chosen options: **Testcontainers PostgreSQL 17** for every integration test, **jqwik properties** for the event
sequencer, and a **PIT mutation gate** (70 % on `domain`, `webhook`, `outbox`, `application`) next to a JaCoCo line
coverage gate (90 % overall and in `domain`).

The resulting pyramid:

| Layer | Tools | Suffix / location | Examples |
|---|---|---|---|
| Architecture | ArchUnit | `ArchitectureTest` | LL-A01, LL-A02, LL-U12 (constant-time compare, in bytecode) |
| Unit | JUnit 6, AssertJ, injected `Clock`, seeded `RandomGenerator` | `*Test` (surefire) | LL-U01 to LL-U14 |
| Property | jqwik | `EventReplayPropertyTest` | LL-P01 |
| Integration | Testcontainers PostgreSQL 17, REST Assured, WireMock | `*IT` (failsafe) | LL-I00 to LL-I23, LL-C01 to LL-C05, LL-N07 |
| Console unit | Vitest, Testing Library, MSW | `*.test.ts(x)` | LL-F01 to LL-F05 |
| End to end | Playwright, axe, against `docker compose` | `ops-console/e2e/` | LL-E01 to LL-E07 |
| Non-functional | k6, OWASP ZAP, Trivy, CodeQL, fresh-clone job | workflows | LL-N01, LL-N04 to LL-N06 |

### Consequences

* Good, because the integration tests run against the same PostgreSQL major version as production, so the
  concurrency tests (LL-I04, LL-I08, LL-I17) test PostgreSQL's real locking, not an emulation.
* Good, because LL-P01 drives the sequencer through 1000 generated histories per run, each duplicated and
  shuffled, and shrinks any failure to a minimal history.
* Good, because the mutation gate fails the build when a changed condition or return value survives the unit
  tests, which coverage cannot detect.
* Bad, because integration tests need Docker and start a container (a `@ServiceConnection` bean, shared by
  test classes with the same configuration through Spring's test context cache), which makes `./mvnw verify` slower than an H2 run.
* Bad, because PIT runs on unit tests only (integration tests are too slow to run per mutant), so logic that is
  only tested through `*IT` classes counts as surviving mutants. This is deliberate: it pushes logic into units.
* Neutral: current counts and scores are not written here; they are in the evidence file CI publishes
  (`evidence/latest.json`), produced from the reports of each run.

### Confirmation

CI: `build-test` runs `./mvnw verify` (unit, integration, JaCoCo gate); the `evidence` job runs PIT on every push to
`main` and `mutation.yml` nightly; `e2e` runs Playwright. AGENTS.md section 5 makes these rules binding for every
change.

## Pros and Cons of the Options

### Testcontainers PostgreSQL 17

* Good, because real SQL, real locks, real `timestamptz`, real Flyway migrations.
* Bad, because Docker is required locally and in CI.

### H2 in PostgreSQL mode

* Good, because fast and dependency-free.
* Bad, because its compatibility mode does not implement PostgreSQL's locking, `SKIP LOCKED` and `ON CONFLICT`
  behaviour faithfully; a green H2 test says little about the guarantees we sell.

### Shared test database

* Bad, because tests interfere with each other and with whoever else uses it.

### jqwik property-based tests

* Good, because they find orderings no one wrote down, and shrink failures to a minimal case.
* Bad, because properties are harder to write than examples and need a reference model (here: the canonical,
  in-order history).

### Examples only

* Good, because easy to read.
* Bad, because the space of duplicated, shuffled histories is too large to enumerate by hand.

### PIT mutation gate

* Good, because it measures whether tests detect changed behaviour.
* Bad, because it is slower than coverage, so it runs on four packages and on unit tests only.

### Line coverage only

* Bad, because a test without assertions earns full coverage.

## More Information

* Run each layer locally: see the README's [Test strategy](../../README.md#6-test-strategy) section.
* Integration test conventions (naming, `Clock`, no sleeps): [AGENTS.md](../../AGENTS.md#5-testing-rules).

# Ledgerline

<!-- Badges. CI and licence are live once the repository is public; the rest are placeholders until those jobs exist. -->
[![CI](https://github.com/specshield-io/ledgerline/actions/workflows/ci.yml/badge.svg)](https://github.com/specshield-io/ledgerline/actions/workflows/ci.yml)
![Coverage](https://img.shields.io/badge/coverage-pending-lightgrey)
![CodeQL](https://img.shields.io/badge/CodeQL-pending-lightgrey)
![OpenSSF Scorecard](https://img.shields.io/badge/OpenSSF%20Scorecard-pending-lightgrey)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)

> [!IMPORTANT]
> **SpecShield Labs, internal demonstration.**
> Ledgerline is an engineering demonstration. It uses **synthetic data only**. It is **not** a
> production payment system and is **not** PCI DSS scoped. Do not use it to process real payments or
> real personal data.

Ledgerline is a reliable order and payment event processing service. The goal is to show, in code and
tests, how to process payment events correctly: idempotency, a transactional outbox, signed webhooks,
retries and failure handling under chaos.

**Status:** domain model, state machine and database schema. No HTTP API or event processing yet.

## Stack

| Area | Choice |
|---|---|
| Runtime | Java 25 (LTS), Spring Boot 4.0.10 |
| Build | Maven 3.10.2 via `./mvnw` |
| Database | PostgreSQL 17, Flyway migrations |
| API docs | springdoc-openapi 3.0.3 (`/v3/api-docs`, `/swagger-ui.html` outside `prod`) |
| Observability | Actuator `health`, `info`, `prometheus` |
| Tests | JUnit 6, Testcontainers 2, ArchUnit, jqwik, WireMock, REST Assured |
| Quality | JaCoCo (gated), PIT (not gated yet), Spotless (palantir-java-format), CycloneDX SBOM |

## Getting started

Prerequisites: JDK 25 and Docker.

```bash
./mvnw verify                 # all tests (integration tests need Docker)
docker compose up --build     # PostgreSQL 17 + app
curl localhost:8080/actuator/health   # {"status":"UP"}
```

If `8080` or `5432` is already taken on your machine, override the host ports:
`LEDGERLINE_HTTP_PORT=18080 LEDGERLINE_DB_PORT=15432 docker compose up --build`.

Using [Colima](https://github.com/abiosoft/colima) instead of Docker Desktop? Testcontainers needs:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Profiles: `local` (default, PostgreSQL on localhost), `test` (Testcontainers), `prod` (all settings from
environment variables `LEDGERLINE_DB_URL`, `LEDGERLINE_DB_USER`, `LEDGERLINE_DB_PASSWORD`).

## Domain model

All money is `long` minor units (cents) in `USD`; all times are UTC `Instant`s. The domain package
(`com.specshield.ledgerline.domain`) has no Spring, JPA or web dependencies (ArchUnit rule LL-A01).

`OrderStateMachine.apply(order, event)` returns `Applied(newStatus)`, `Stale(reason)` or `Ignored(reason)`, or
throws `IllegalTransitionException`. Checks run in this order:

1. Order `CANCELLED`: every event is **Ignored**.
2. Event sequence at or below the last applied sequence: **Stale** (out of order or replayed).
3. `PAYMENT_FAILED` once the order is `PAID`, `PARTIALLY_REFUNDED` or `REFUNDED`: **Stale**.
4. Transitions:

| From | Event | To |
|---|---|---|
| `CREATED` | `PAYMENT_INITIATED` | `PAYMENT_PENDING` |
| `PAYMENT_PENDING` | `PAYMENT_SUCCEEDED` | `PAID` |
| `PAYMENT_PENDING` | `PAYMENT_FAILED` | `PAYMENT_FAILED` |
| `PAYMENT_FAILED` | `PAYMENT_SUCCEEDED` | `PAID` |
| `PAID` / `PARTIALLY_REFUNDED` | `REFUND_SUCCEEDED` | `PARTIALLY_REFUNDED` (amount below refundable) or `REFUNDED` (equal) |
| `CREATED` | `cancel()` | `CANCELLED` |

Anything else, including a refund above the refundable amount, throws. `OrderStateMachineMatrixTest` (LL-U05)
pins every (status, event) pair.

**Event ordering.** Because a lower sequence is Stale, applying events in arrival order would lose an
out-of-order refund (refund #5 first makes #4 Stale). `OrderEventSequencer` is the single place that applies an
order's pending events: it drops repeated `providerEventId`s, sorts by sequence, applies up to the next expected
sequence and leaves events after a gap waiting. It assumes provider sequences have no gaps; an event that keeps
waiting means a missing predecessor and must be surfaced, never applied out of order. The jqwik property LL-P01
drives this sequencer through 1000 generated histories, each duplicated (0 to 4 extra copies per event) and
shuffled, and checks it reaches the same final status and refunded amount as the canonical history.

## Database schema

Flyway `V1__init.sql` (PostgreSQL 17): `orders`, `order_events_inbox` (unique on `provider, provider_event_id`),
`audit_log`, `idempotency_keys`, `outbox` (partial index on `next_attempt_at` where `status = 'PENDING'`).
Check constraints mirror the domain invariants (amount range, `USD`, status list, refunded amount consistent
with status). Hibernate only validates the schema.

## Repository layout

```
ledgerline-api/   Spring Boot service
ops-console/      React operations console (later step)
```

## Contributing

Read [AGENTS.md](AGENTS.md) first. It holds the architecture rules, testing rules and Definition of Done.
Report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Licence

[Apache-2.0](LICENSE)

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

**Status:** domain model, schema, the public orders API with idempotency, the MockPay simulator sending real signed webhooks, the hardened webhook receiver, signed merchant notifications through a transactional outbox, a public chaos API with rate limiting, role-based admin access and self-resetting demo data, and the operations console that shows it all live.

## Stack

| Area | Choice |
|---|---|
| Runtime | Java 25 (LTS), Spring Boot 4.0.10 |
| Build | Maven 3.10.2 via `./mvnw` |
| Database | PostgreSQL 17, Flyway migrations |
| API docs | springdoc-openapi 3.0.3 (`/v3/api-docs`, `/swagger-ui.html` outside `prod`) |
| Observability | Actuator `health`, `info`, `prometheus` |
| Console | React 19, TypeScript (strict), Vite 8, TanStack Query, React Router, Tailwind CSS 4, Radix UI, lucide |
| Tests | JUnit 6, Testcontainers 2, ArchUnit, jqwik, WireMock, REST Assured; Vitest, Testing Library, MSW, Playwright, axe |
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
environment variables `LEDGERLINE_DB_URL`, `LEDGERLINE_DB_USER`, `LEDGERLINE_DB_PASSWORD`, plus the secrets and
accounts listed under [Security](#security)).

## Operations console

`docker compose up --build`, then open **http://localhost:8080**. The console runs the chaos scenarios and shows,
live, what the service does with them:

- **Scenarios** (left): the eight failure modes, each with what to watch for. A run opens its new order.
- **Orders**: id, merchant reference, amount, status (text and icon, not colour alone), last update; filter by status.
- **Order timeline**: inbox, audit log and outbox merged, labelled Received, Applied, Deduplicated, Stale (out of
  order), Signature rejected, Notification queued, Retry scheduled (with the next attempt), Outbox delivered,
  Dead-lettered. It refreshes every second while a scenario runs, every 5 s otherwise.
- **Outbox** (sign in as VIEWER or ADMIN; local accounts in [Security](#security)): attempts, last error, next attempt;
  ADMINs can replay dead letters after a confirmation.
- **Metrics strip**: webhooks received, events applied, duplicates blocked, signatures rejected, outbox pending
  (`GET /api/v1/stats`, the same meters as `/actuator/prometheus`, counted since the instance started).
- **How it works**: the architecture diagram and links to the ADRs.

Light and dark follow the system setting; it works with a keyboard and down to 360 px wide. Tests: Vitest unit tests
(`LL-F01`..`LL-F05`) and Playwright end-to-end tests (`LL-E01`..`LL-E07`, including axe accessibility checks and phone
screenshots) run in CI. Details in [ops-console/README.md](ops-console/README.md).

## Try it

Start the stack (`docker compose up --build`), then break things. Each scenario creates its **own** synthetic order
and drives it through a failure mode with real signed HTTP webhooks from MockPay; it never touches existing orders.
The answer is `202 {"scenarioRunId": ..., "orderId": ...}`; watch the run, or the order's timeline, which shows every
step (`CHAOS_STEP`), every provider event with its outcome, and every audit row.

```bash
B=http://localhost:8080
start() { curl -s -X POST "$B/api/v1/chaos/scenarios/$1" -H "Idempotency-Key: $(uuidgen)"; echo; }

start happy-path        # create + pay through MockPay                       -> PAID
start duplicate-storm   # the success event delivered 5 times at once        -> PAID, applied once, 4 DUPLICATE
start tampered-webhook  # body altered after signing                         -> 400, REJECTED, stays PAYMENT_PENDING
start replay-attack     # valid signature, timestamp 10 minutes old          -> 400, REJECTED, stays PAYMENT_PENDING
start out-of-order      # SUCCEEDED (seq 2), then a late FAILED (seq 1)      -> PAID, one STALE
start merchant-down     # demo merchant answers 500 for 2 min                -> order.paid retried, DELIVERED after
start merchant-slow     # demo merchant takes 10 s for 2 min                 -> deliveries time out at 3 s, retried
start refund-flow       # pay, refund 15.00, refund the remaining 34.99      -> REFUNDED

# Follow a run (steps with the receiver's HTTP status), then the order's timeline:
curl -s "$B/api/v1/chaos/runs/<scenarioRunId>"
curl -s "$B/api/v1/orders/<orderId>/timeline"
# The merchant's side of merchant-down / merchant-slow, and the outbox (VIEWER account, local profile):
curl -s "$B/demo/merchant-sink"
curl -s -u local-viewer:local-dev-only-viewer-password "$B/api/v1/admin/outbox?status=PENDING"
```

`GET /api/v1/chaos/scenarios` lists the scenarios. Limits: 10 scenario starts per minute per client, and 3 runs in
progress at once across everyone (`429` problem with `Retry-After`). The same `Idempotency-Key` returns the same run.
merchant-down and merchant-slow switch the shared demo merchant, so they affect everyone's notifications for
2 minutes; with the backoff, the delayed `order.paid` usually arrives a few minutes after the outage ends.

The demo data (25 synthetic orders, `demo-1001` to `demo-1025`, across every status) is seeded when the database is
empty and wiped and reseeded every night at 03:00 UTC. `GET /api/v1/meta` returns the banner:
`{"demo":true,"dataResetsAt":"03:00 UTC","version":"...","gitSha":"..."}`.

## API

Base path `/api/v1`. The full contract is [`docs/openapi.json`](docs/openapi.json) (OpenAPI 3.1, generated by the
build and checked in CI); a running app also serves it at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.
There is no authentication: this is a demonstration with synthetic data.

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/api/v1/orders` | Create an order (needs `Idempotency-Key`) | `201` + `Location` |
| `GET` | `/api/v1/orders/{id}` | Read one order | `200` |
| `GET` | `/api/v1/orders?status=&page=&size=` | List, newest first; `size` at most 100 | `200` |
| `POST` | `/api/v1/orders/{id}/pay` | Ask MockPay to start a payment (needs `Idempotency-Key`) | `202` |
| `POST` | `/api/v1/orders/{id}/cancel` | Cancel a `CREATED` order (needs `Idempotency-Key`) | `200` |
| `GET` | `/api/v1/orders/{id}/timeline` | Audit log and provider events, oldest first | `200` |

```bash
# Create (repeat it: same 201 body, plus "Idempotent-Replayed: true")
curl -i -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-0001' \
  -d '{"merchantRef":"synthetic-order-1001","amountMinor":2599,"currency":"USD"}'

# Read one
curl localhost:8080/api/v1/orders/$ORDER_ID

# List
curl 'localhost:8080/api/v1/orders?status=CREATED&page=0&size=10'

# Request payment
curl -X POST localhost:8080/api/v1/orders/$ORDER_ID/pay -H 'Idempotency-Key: demo-pay-0001'

# Cancel
curl -X POST localhost:8080/api/v1/orders/$ORDER_ID/cancel -H 'Idempotency-Key: demo-cancel-0001'

# Timeline
curl localhost:8080/api/v1/orders/$ORDER_ID/timeline
```

### Idempotency

State-changing `POST`s require `Idempotency-Key` (8 to 128 characters, `[A-Za-z0-9_-]`). The key is bound to a
SHA-256 of method, path and canonical JSON body (keys sorted, whitespace ignored). Keys expire after 24 hours and
an hourly job deletes them.

| Situation | Response |
|---|---|
| First request | Executed; the response (status, body, `Content-Type`, `Location`) is stored |
| Same key, same request, completed | Stored response replayed byte for byte, with `Idempotent-Replayed: true` |
| Same key, different request | `422` `idempotency-key-reuse` |
| Same key while the first is still running | `409` `idempotency-request-in-progress`, `Retry-After: 1` |
| First request failed with 5xx | Nothing stored; retrying with the same key executes again |
| First request failed with 4xx | Stored and replayed like a success |

Known limitation: claiming the key, the business transaction and storing the response are separate
transactions. If the process dies between the business commit and storing the response, the key stays
`IN_PROGRESS` (409) until it expires.

### Errors and tracing

Every error is RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance` and
`correlationId`. Types are stable URNs such as `urn:ledgerline:problem:order-not-found`; branch on `type`.
Send `X-Request-Id: <uuid>` to choose the correlation id (anything else is replaced by a generated UUID); it is
echoed on the response, written to every log line of the request and stored on audit rows.

## MockPay and webhooks

MockPay is an in-process payment-provider **simulator** (`ledgerline.mockpay.enabled`, on in the `local`
profile). It never contacts a real payment network. `POST /pay` records the payment in MockPay's own
`mockpay_ledger` and, after the transaction commits, sends real HTTP webhooks from a virtual thread to
`/webhooks/mockpay`: `PAYMENT_INITIATED` at once, then `PAYMENT_SUCCEEDED` (or `PAYMENT_FAILED`, see
`ledgerline.mockpay.default-outcome`) after 300 to 800 ms. A 5xx or unreachable receiver is retried with
exponential backoff.

```json
{"id":"evt_...","type":"PAYMENT_SUCCEEDED","created":1768471200,"sequence":2,
 "data":{"orderId":"...","amountMinor":2599,"currency":"USD"}}
```

Each delivery is signed like Stripe's: `MockPay-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">`,
with one `v1` per configured secret during rotation.

The receiver verifies the raw bytes (constant-time compare, 300 s tolerance), stores the event in the inbox and,
in the same transaction, locks the order and applies its pending events in sequence order. Responses:

| Delivery | Response | Inbox outcome |
|---|---|---|
| Valid, applied (or waiting for an earlier event) | `200 {"received":true}` | `APPLIED` (or pending) |
| Already stored | `200 {"received":true,"duplicate":true}` | unchanged |
| Unknown event type | `200` | `IGNORED` |
| Order not found yet | `200` | `ORPHAN`, retried every 30 s for 1 h |
| Bad, expired or missing signature | `400` `invalid-signature` | `REJECTED` (synthetic id) |
| Validly signed but not an event | `400` `malformed-event` | none |
| Unexpected failure | `500`, everything rolled back | none (the provider retries) |

Metrics (Prometheus): `ledgerline_webhook_received_total`, `_applied_total`, `_duplicates_total`,
`_stale_total`, `_rejected_total{reason}`, `_failures_total`, and the timer `ledgerline_webhook_processing_seconds`.
Every 5 minutes, reconciliation compares MockPay's ledger with order statuses and records differences as
`RECONCILIATION_MISMATCH` audit rows. The design choices are in
[ADR 0002](docs/adr/0002-webhook-signature-and-inbox.md).

Configuration: `ledgerline.webhook.mockpay.secrets` (receiver; required, at least 32 characters each; in `prod`
from `LEDGERLINE_MOCKPAY_WEBHOOK_SECRETS`, comma separated) and `ledgerline.mockpay.signing-secrets` (simulator).
The values in `application.yml` are synthetic local and test values.

## Merchant notifications (transactional outbox)

When an order becomes `PAID`, `PAYMENT_FAILED`, `REFUNDED`, `PARTIALLY_REFUNDED` or `CANCELLED`, an `outbox` row
is written **in the same transaction** as the change, so the notification exists if and only if the change
commits. The relay (`outbox.OutboxDispatcher`, every 500 ms) claims up to 20 due rows with
`FOR UPDATE SKIP LOCKED`, marks them `IN_FLIGHT`, and POSTs each to `ledgerline.merchant.url`:

```
POST <merchant url>
Content-Type: application/json
Ledgerline-Signature: t=1768471200,v1=<hex HMAC-SHA256 of "t.body">
Ledgerline-Event-Id: 42
Ledgerline-Delivery-Attempt: 1

{"id":"42","type":"order.paid","createdAt":"2026-01-15T10:00:00Z",
 "data":{"orderId":"...","merchantRef":"...","status":"PAID","previousStatus":"PAYMENT_PENDING",
         "amountMinor":2599,"refundedMinor":0,"currency":"USD","occurredAt":"2026-01-15T10:00:00Z"}}
```

- **2xx**: `DELIVERED`. Anything else, or no response within 3 s (connect timeout 2 s): the attempt is counted,
  `last_error` is stored, and the row is retried after an exponential backoff with full jitter (base 2 s, cap 1 h).
  After 8 attempts the row is `DEAD`.
- **At-least-once, unordered.** A row can be delivered more than once (crash recovery, lost acknowledgements), and
  notifications for one order can arrive out of order. **Receivers must deduplicate by `Ledgerline-Event-Id`**
  and use `status`/`occurredAt` rather than arrival order.
- **Crash recovery.** A watchdog returns rows `IN_FLIGHT` for more than 60 s to `PENDING`.
- **Verify the signature** on the raw body exactly as for MockPay webhooks, with the merchant secret
  (`ledgerline.merchant.signing-secrets`; in `prod` from `LEDGERLINE_MERCHANT_SIGNING_SECRETS`, with the URL from
  `LEDGERLINE_MERCHANT_URL`).

Operator endpoints (HTTP Basic; see [Security](#security); the authenticated user is recorded in the audit log):

```bash
# List, newest first; filter by PENDING, IN_FLIGHT, DELIVERED or DEAD (VIEWER or ADMIN)
curl -s -u local-viewer:local-dev-only-viewer-password 'localhost:8080/api/v1/admin/outbox?status=DEAD'
# Send a DEAD message again (ADMIN; attempts reset; OUTBOX_REPLAYED audit row with actor operator:local-admin)
curl -s -u local-admin:local-dev-only-admin-password -X POST localhost:8080/api/v1/admin/outbox/42/replay \
  -H 'Idempotency-Key: replay-42-0001'
```

In the `local` profile the notifications go to a built-in **demo merchant**, `POST /demo/merchant-sink`, which
verifies the signature, deduplicates by event id and records what it received (`GET /demo/merchant-sink`). The
merchant-down and merchant-slow chaos scenarios make it fail (500) or answer slowly for 2 minutes. It is off in `prod` and not
part of the OpenAPI document.

Metrics (Prometheus): `ledgerline_outbox_pending`, `ledgerline_outbox_oldest_pending_age_seconds`,
`ledgerline_outbox_delivered_total`, `ledgerline_outbox_failed_total{reason}`, `ledgerline_outbox_dead_total`,
`ledgerline_outbox_watchdog_released_total` and the timer `ledgerline_outbox_delivery_seconds{outcome}`. Why an
outbox rather than dual-write, why no broker yet, and how to move to Kafka or SQS:
[ADR 0003](docs/adr/0003-transactional-outbox.md).

## Security

| Who | May use |
|---|---|
| Anyone (rate limited) | the console (`/`, `/outbox`, `/assets/**`), `GET/POST /api/v1/orders/**`, `/api/v1/chaos/**`, `/api/v1/meta`, `/api/v1/stats`, `/actuator/health`, `/actuator/info`, `/v3/api-docs`, Swagger UI |
| Signed callers | `POST /webhooks/mockpay` (MockPay HMAC), `/demo/merchant-sink` (Ledgerline HMAC) |
| `VIEWER` (HTTP Basic) | `GET /api/v1/admin/**`, `/actuator/prometheus` |
| `ADMIN` (HTTP Basic) | everything above, plus `POST /api/v1/admin/outbox/{id}/replay` and `POST /api/v1/admin/reset` |

Everything else is `403`. An anonymous request to an admin path is `401` with `WWW-Authenticate: Basic`. Both are
problem+json.

- **Accounts** come from configuration as **BCrypt hashes**, never passwords. In `prod`:
  `LEDGERLINE_ADMIN_USERNAME` and `LEDGERLINE_ADMIN_PASSWORD_HASH` (required; startup fails without them), and
  optionally `LEDGERLINE_VIEWER_USERNAME` and `LEDGERLINE_VIEWER_PASSWORD_HASH`. Make a hash with, for example,
  `htpasswd -nbBC 12 "" 'your-password' | cut -d: -f2`. The `local` and `test` profiles contain synthetic accounts
  whose passwords are written next to them in `application.yml`; they are not secrets.
- **Rate limits** (Bucket4j, in memory, per instance, per client IP): 240 requests per minute to the API and its
  documentation (the console polls; one visitor uses at most about 150 per minute), 10 chaos scenario starts per
  minute. Over the limit: `429` problem+json with `Retry-After`.
  Webhooks are authenticated by signature and not limited. Limits apply before authentication, so floods with bogus
  credentials cost no BCrypt work. With N instances the effective limit is N times higher.
- **Client IP.** `X-Forwarded-For` is trusted only when the TCP peer is in `LEDGERLINE_TRUSTED_PROXIES` (comma
  separated CIDRs of the Fly.io or Cloudflare proxies in front of the app), and is then read from the right. Unset,
  the header is ignored. The proxy ranges for a given deployment must be configured there; none are built in.
- **Headers** on every response: `Strict-Transport-Security: max-age=31536000; includeSubDomains`,
  `X-Content-Type-Options: nosniff`, `Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin`.
- **CORS**: `/api/**` from `https://www.specshield.example` (plus `http://localhost:5173` and `:3000` in `local`),
  methods GET and POST, no credentials.
- **CSRF** protection is off only for `/api/**`, `/webhooks/**` and the demo merchant: they use no cookies or sessions,
  and every state-changing API call needs the `Idempotency-Key` header, which a browser cannot send cross-origin
  without a CORS preflight.
- **Request bodies** are at most 64 KiB (`413`, also for chunked bodies) and must be JSON (`415`).

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

Flyway `V1__init.sql` to `V5__shedlock.sql` (PostgreSQL 17): `orders`, `order_events_inbox` (unique on
`provider, provider_event_id`; `order_id` for the timeline), `audit_log`, `idempotency_keys` (raw response bytes
for exact replay), `mockpay_ledger` (the simulator's own record), `outbox` (partial index on `next_attempt_at` where `status = 'PENDING'`; `claimed_at` set only while
`IN_FLIGHT`), `shedlock` (the lock that keeps the demo reset from running twice at once).
Check constraints mirror the domain invariants (amount range, `USD`, status list, refunded amount consistent
with status). Hibernate only validates the schema.

## Repository layout

```
ledgerline-api/   Spring Boot service
ops-console/      React operations console, built into the jar (see ops-console/README.md)
```

## Contributing

Read [AGENTS.md](AGENTS.md) first. It holds the architecture rules, testing rules and Definition of Done.
Report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Licence

[Apache-2.0](LICENSE)

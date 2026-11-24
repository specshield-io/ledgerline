# Ledgerline runbook

Operational procedures for Ledgerline, a SpecShield Labs internal demonstration with **synthetic data only**. It
is not a production payment system; these procedures show how we would run it, and they work against the stack
in this repository.

Contents: [Deploy](#deploy) · [Reset the demo data](#reset-the-demo-data) ·
[Rotate a webhook secret with zero downtime](#rotate-a-webhook-secret-with-zero-downtime) ·
[Read the metrics](#read-the-metrics) · [Common incidents](#common-incidents)

## Deploy

The service is one container image (API and console) plus PostgreSQL 17. The repository has no deployment
manifest for a hosting provider; the steps below apply to any container platform.

### 1. Build the image

```bash
docker build --build-arg GIT_SHA=$(git rev-parse --short HEAD) -t ledgerline-api:$(git rev-parse --short HEAD) .
```

The build runs `./mvnw package` inside the image (tests run in CI, not in the image build). CI scans the same
image with Trivy and fails on any CRITICAL vulnerability (LL-N05).

### 2. Configure the `prod` profile

Every value below is required unless marked optional; with one missing, startup fails instead of running with a
default.

| Variable | Meaning |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | JSON (ECS) logs, Swagger UI off, no built-in secrets or accounts |
| `LEDGERLINE_DB_URL`, `LEDGERLINE_DB_USER`, `LEDGERLINE_DB_PASSWORD` | PostgreSQL 17 (JDBC URL) |
| `LEDGERLINE_MOCKPAY_WEBHOOK_SECRETS` | Webhook receiver secrets, comma separated, at least 32 characters each |
| `LEDGERLINE_MERCHANT_URL` | Where merchant notifications are POSTed |
| `LEDGERLINE_MERCHANT_SIGNING_SECRETS` | Secrets that sign merchant notifications, comma separated |
| `LEDGERLINE_ADMIN_USERNAME`, `LEDGERLINE_ADMIN_PASSWORD_HASH` | ADMIN account; BCrypt hash, never a password |
| `LEDGERLINE_VIEWER_USERNAME`, `LEDGERLINE_VIEWER_PASSWORD_HASH` | Optional VIEWER account |
| `LEDGERLINE_TRUSTED_PROXIES` | Optional CIDRs of the proxies in front of the app; unset, `X-Forwarded-For` is ignored |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS` | Optional OTLP/HTTP export of traces and metrics |

Make a hash with `htpasswd -nbBC 12 "" 'the-password' | cut -d: -f2`. Keep secrets in the platform's secret store,
never in the repository or the image.

### 3. Roll out

1. **Migrations run at startup** (Flyway, forward-only, `V<n>__*.sql`). Every migration must work with the
   previous application version still running, because during a rolling deploy both versions share the database.
   Hibernate only validates the schema; a mismatch fails startup, before traffic.
2. **Start new instances** and wait for `GET /actuator/health` to return `{"status":"UP"}` (it includes the
   database).
3. **Stop old instances** with SIGTERM. Shutdown is graceful (`server.shutdown: graceful`, 20 s per phase); give
   the platform at least 30 s before SIGKILL (as `docker-compose.yml` does).
4. **What a deploy can interrupt, and why it is safe:**
   - An outbox delivery in flight when an instance dies stays `IN_FLIGHT`; the watchdog returns it to `PENDING`
     after 60 s and it is delivered again. Merchants deduplicate by `Ledgerline-Event-Id`
     ([ADR 0003](adr/0003-transactional-outbox.md)).
   - A webhook interrupted before commit rolls back completely; the provider retries and it is processed as new.
   - A request interrupted between its business commit and storing its idempotent response leaves the key
     `IN_PROGRESS` (409) until it expires after 24 h ([ADR 0005](adr/0005-idempotency-key-design.md)).
5. **Verify**: `GET /api/v1/meta` reports the new `gitSha`; `ledgerline_outbox_pending` drains; no rise in
   `ledgerline_webhook_failures_total`.

**Rollback**: deploy the previous image. Because migrations are forward-only and backward compatible, the previous
version runs on the newer schema. Never edit or delete an applied migration.

**More than one instance**: safe for the outbox relay (`SKIP LOCKED`, [ADR 0004](adr/0004-postgres-skip-locked-over-a-broker.md))
and the nightly reset (ShedLock). Rate limits are per instance, so the effective limit is N times higher.

### Local stack

```bash
docker compose up --build --wait     # PostgreSQL 17 + app on http://localhost:8080
docker compose down                  # stop, keep data
docker compose down -v               # stop and delete the database volume
```

## Reset the demo data

The demo data is 25 synthetic orders (`demo-1001` to `demo-1025`) across every status.

- **Automatically**: every night at 03:00 UTC (`demo.DemoReset`), under the ShedLock lock `demo-data`, so with
  several instances it runs once.
- **On demand** (ADMIN):

  ```bash
  curl -s -X POST -u "$ADMIN_USER:$ADMIN_PASSWORD" -H "Idempotency-Key: reset-$(uuidgen)" \
    https://<host>/api/v1/admin/reset
  # {"reset":true,"seededOrders":25}
  ```

  `409` means a reset is already running elsewhere (the lock is held); wait and retry. Locally the account is
  `local-admin` / `local-dev-only-admin-password` (synthetic, in `application.yml`).
- **From scratch, locally**: `docker compose down -v && docker compose up --build --wait`; an empty database is
  seeded at startup.

A reset deletes every order and its inbox, audit and outbox rows, then reseeds. It does not reset the in-memory
metric counters (`/api/v1/stats` counts since the instance started).

## Rotate a webhook secret with zero downtime

Both directions accept and produce **several secrets at once**, so a secret can be replaced without a single valid
delivery failing. Secrets are read at startup: each step below is a configuration change followed by a rolling
restart. With one instance a restart is a short outage; deliveries during it are retried by the sender and still
verify, because no step removes a secret that is still in use.

### Inbound: provider to Ledgerline (`LEDGERLINE_MOCKPAY_WEBHOOK_SECRETS`)

The receiver accepts a delivery when **any** `v1` in `MockPay-Signature` matches **any** configured secret
(LL-U12).

1. **Add** the new secret next to the old one: `LEDGERLINE_MOCKPAY_WEBHOOK_SECRETS=<old>,<new>`. Roll out.
2. **Switch the provider** to sign with the new secret (in the provider's dashboard; for MockPay,
   `ledgerline.mockpay.signing-secrets`, which signs with every listed secret, one `v1` each).
3. **Wait** longer than the provider's retry horizon, so no retry signed with the old secret is still pending.
   Check `ledgerline_webhook_rejected_total{reason}` stays flat.
4. **Remove** the old secret: `LEDGERLINE_MOCKPAY_WEBHOOK_SECRETS=<new>`. Roll out.

### Outbound: Ledgerline to merchants (`LEDGERLINE_MERCHANT_SIGNING_SECRETS`)

Ledgerline signs each notification with **every** configured secret, one `v1` per secret in
`Ledgerline-Signature`.

1. **Add** the new secret: `LEDGERLINE_MERCHANT_SIGNING_SECRETS=<old>,<new>`. Roll out. Every notification now
   carries two `v1` values, and merchants still verifying with the old secret keep working.
2. **Ask the merchant** to verify with the new secret (a receiver that accepts any matching `v1` needs no other
   change).
3. **Remove** the old secret: `LEDGERLINE_MERCHANT_SIGNING_SECRETS=<new>`. Roll out. Rows still pending are signed
   at delivery time, so they carry the new signature.

If a secret leaks, do the same steps without waiting, and accept that deliveries signed with the leaked secret are
rejected once it is removed; the senders retry them, newly signed.

## Read the metrics

| Where | Who | What |
|---|---|---|
| `GET /api/v1/stats` | anyone | The console's metrics strip: received, applied, duplicates blocked, signatures rejected, outbox pending and dead, `countingSince` |
| `GET /actuator/prometheus` | VIEWER or ADMIN | Every meter in Prometheus format |
| OTLP | your collector | Traces and metrics, when `OTEL_EXPORTER_OTLP_ENDPOINT` is set |

```bash
curl -s localhost:8080/api/v1/stats
curl -s -u local-viewer:local-dev-only-viewer-password localhost:8080/actuator/prometheus | grep '^ledgerline_'
```

Counters are per instance and reset on restart; use `rate()` / `increase()` across instances.

### What to alert on

| Signal | Meaning | Suggested alert (not yet configured anywhere) |
|---|---|---|
| `ledgerline_outbox_oldest_pending_age_seconds` | Age of the oldest undelivered notification | above 15 min: a merchant is down or the relay is stuck |
| `ledgerline_outbox_dead_total` | Notifications that exhausted 8 attempts | any increase: replay after the merchant recovers (below) |
| `ledgerline_outbox_pending` | Backlog | sustained growth |
| `ledgerline_outbox_watchdog_released_total` | Deliveries abandoned by a dying instance | increases outside deploys |
| `ledgerline_webhook_rejected_total{reason}` | Invalid, expired or missing signatures | sudden rise: misconfigured secret (after a rotation) or an attack |
| `ledgerline_webhook_failures_total` | Webhooks answered 500 and rolled back | any sustained rate |
| `ledgerline_webhook_duplicates_total`, `_stale_total` | Duplicates and out-of-order events absorbed | informational; this is the system working |
| `ledgerline_webhook_processing_seconds`, `ledgerline_outbox_delivery_seconds{outcome}` | Latency timers | p95 regressions |

Reconciliation also writes `RECONCILIATION_MISMATCH` audit rows every 5 minutes when MockPay's ledger and order
statuses disagree; they appear on the order's timeline.

### Correlating a request

Send `X-Request-Id: <uuid>` (or read the generated one from the response). It is on every log line of the request
(`correlationId`), on audit rows, and in `prod` JSON logs next to `traceId`, `spanId` and `orderId`.

## Common incidents

**A merchant was down and notifications are DEAD.**

```bash
curl -s -u "$VIEWER_USER:$VIEWER_PASSWORD" 'https://<host>/api/v1/admin/outbox?status=DEAD'
curl -s -X POST -u "$ADMIN_USER:$ADMIN_PASSWORD" -H "Idempotency-Key: replay-<id>-$(date +%s)" \
  https://<host>/api/v1/admin/outbox/<id>/replay
```

Or sign in to the console's Outbox page as ADMIN and use Replay. Each replay writes an `OUTBOX_REPLAYED` audit row
naming the operator.

**`ledgerline_webhook_rejected_total` jumps after a deploy.** A secret was removed or mistyped. Restore the previous
`LEDGERLINE_MOCKPAY_WEBHOOK_SECRETS` value; the provider retries the rejected deliveries.

**Clients get `409 idempotency-request-in-progress` for a long time.** The process died between commit and storing
the response. The key clears after 24 h; check the order's state with `GET /api/v1/orders/{id}` meanwhile.

**Public clients get `429`.** 240 requests per minute per IP on the API, 10 chaos starts per minute. Behind a proxy,
check `LEDGERLINE_TRUSTED_PROXIES`; unset, every client appears to share the proxy's IP.

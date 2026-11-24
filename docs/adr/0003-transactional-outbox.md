---
status: accepted
date: 2026-11-24
decision-makers: SpecShield Labs engineering
---

# ADR 0003: Transactional outbox for merchant notifications

## Context and Problem Statement

When an order changes state in a way a merchant cares about (paid, payment failed, refunded, partially
refunded, cancelled), the merchant must be told through an HTTP POST to its endpoint. The state change lives in
PostgreSQL. The notification goes over the network to a system we do not control, which can be slow, down or
answer 500 for hours. We need:

1. no lost notifications: if the change commits, the merchant eventually hears about it;
2. no phantom notifications: if the change rolls back, the merchant never hears about it;
3. bounded, polite retries that do not hammer a merchant that is recovering;
4. a way for an operator to see what failed and to send it again.

How do we commit an order change and tell an external HTTP endpoint about it, when no transaction spans both?

## Decision Drivers

* No lost and no phantom notifications (requirements 1 and 2).
* Never hold a row lock or a pooled connection while waiting on a merchant.
* Polite, bounded retries; failures visible and replayable by an operator.
* No new infrastructure unless it removes a problem we actually have.

## Considered Options

* Dual-write: commit, then POST (or POST, then commit)
* POST inside the database transaction
* Distributed transaction (2PC/XA)
* Transactional outbox table plus a relay

## Decision Outcome

Chosen option: "Transactional outbox table plus a relay", because it is the only option that makes the change and
the notification atomic with the tools we have. The relay is a polling dispatcher using `FOR UPDATE SKIP LOCKED`
([ADR 0004](0004-postgres-skip-locked-over-a-broker.md) explains why not a broker). The details follow.

### 1. Outbox, not dual-write

**Dual-write** means committing the order and then calling the merchant (or calling first and committing after).
Every ordering of the two has a failure window:

| Order of operations | Failure between the two steps | Result |
|---|---|---|
| Commit, then POST | Crash, timeout or deploy after the commit | Change committed, **notification lost** |
| POST, then commit | Commit fails (constraint, deadlock, crash) | Merchant told about a change that **never happened** |
| POST inside the transaction | Merchant slow | Row locks and a pooled connection held for seconds; the same two failures remain |

A distributed transaction (2PC/XA) between PostgreSQL and an HTTP endpoint does not exist.

**Outbox**: the notification is written as a row in the `outbox` table, **in the same transaction** as the order
change (`application.MerchantNotifications`, called by `PaymentEventService` and `OrderService.cancel`). The
database guarantees both or neither (LL-I13). A separate relay reads committed rows and delivers them. This turns
"commit and notify atomically", which cannot be done, into "commit atomically, then deliver until acknowledged",
which can.

Only outcomes a merchant acts on are written: `order.paid`, `order.payment_failed`, `order.refunded`,
`order.partially_refunded`, `order.cancelled`. `PAYMENT_PENDING` is an internal step and produces no row.

### 2. A polling relay with `FOR UPDATE SKIP LOCKED`

`outbox.OutboxDispatcher` runs every 500 ms:

1. **Claim**, in one short transaction: select up to 20 `PENDING` rows with `next_attempt_at <= now`, oldest id
   first, `FOR UPDATE SKIP LOCKED`; mark them `IN_FLIGHT` with `claimed_at = now`; commit.
2. **Deliver** each row on a virtual thread, **outside any transaction**, so no row lock or pooled connection is
   held during a slow HTTP call.
3. **Record** each result with a conditional update `WHERE id = ? AND status = 'IN_FLIGHT' AND claimed_at = ?`:
   `DELIVERED` on 2xx; otherwise one more attempt, `last_error` (at most 500 characters, also a database
   constraint), and `PENDING` with a backoff delay, or `DEAD`.

`SKIP LOCKED` lets several dispatchers (threads, or application instances) run at once on disjoint rows, without
waiting for each other and without a leader election. In LL-I17, two dispatchers over 200 rows delivered each row
exactly once. The `claimed_at` condition means a dispatcher whose row was meanwhile released by the watchdog
cannot overwrite the newer state.

HTTP client: `java.net.http.HttpClient`, connect timeout 2 s, response timeout 3 s (LL-I18), no redirects.

### 3. Exponential backoff with full jitter, then DEAD

`outbox.BackoffPolicy` is a pure class with an injected `RandomGenerator` (LL-U14). The delay before attempt `n`
is uniform in `[0, min(cap, base * 2^n)]`, with base 2 s and cap 1 h. After 8 attempts the row is `DEAD`.

Full jitter (rather than a fixed or "equal jitter" delay) spreads the retries of many rows that failed together,
for example during a merchant outage, across the whole window, so the merchant is not hit by synchronised waves
when it recovers.

With these settings the upper bounds before attempts 2 to 8 are 8 s, 16 s, ... 512 s, so a row spends at
most about 17 minutes waiting between its first and last attempt (1,016 s, the sum of the bounds; about half
that on average), plus the attempts themselves (at most 5 s each). The
1 h cap does not bind at 8 attempts; it is there for when `ledgerline.outbox.max-attempts` is raised. `DEAD` rows
are never retried automatically. They are listed by `GET /api/v1/admin/outbox?status=DEAD` and sent again by
`POST /api/v1/admin/outbox/{id}/replay` (attempts reset, audit row naming the operator; LL-I16).

### 4. At-least-once delivery; receivers deduplicate

A dispatcher can crash, or be killed in a deploy, after claiming a row and before recording the result. The
merchant may or may not have received the request. `outbox.OutboxWatchdog` returns rows that have been
`IN_FLIGHT` for more than 60 s to `PENDING` (LL-I20); the lost attempt is not counted. 60 s is far above the
longest possible attempt (2 s connect + 3 s response), so healthy deliveries are not released.

The consequence is deliberate: **a notification can be delivered more than once** (after a crash, a watchdog
release, or a 2xx lost on the way back). Exactly-once delivery over HTTP is not achievable; at-least-once with
deduplication is. Every request carries `Ledgerline-Event-Id` (the outbox id, stable across attempts), and the
body is identical on every attempt. **Receivers must deduplicate by `Ledgerline-Event-Id`**, as the built-in demo
merchant (`/demo/merchant-sink`) does.

**No ordering guarantee.** Rows are claimed by id, but retries and parallel delivery mean a merchant can receive
`order.refunded` before a retried `order.paid` for the same order. Each payload carries the full current status,
`previousStatus` and `occurredAt`; receivers should ignore a notification older than the state they already
have, or read the order with `GET /api/v1/orders/{id}`.

### 5. Signatures: the same scheme as MockPay, a separate secret

`Ledgerline-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">`, one `v1` per configured secret during
rotation (`webhook.WebhookSigner`, shared with MockPay). Reusing the scheme means one well-tested implementation
and one verification recipe for merchants (ADR 0002 explains the scheme). The secret is separate
(`ledgerline.merchant.signing-secrets`): a leaked merchant secret must not let anyone forge MockPay events to us,
and the reverse.

### 6. No message broker yet

We have one consumer type (a merchant HTTP endpoint), modest volume, and PostgreSQL already. A broker (Kafka,
RabbitMQ, SQS) would add infrastructure to run, monitor and secure, and **would not remove the outbox**:
publishing to a broker from the request transaction is dual-write again. With or without a broker, the outbox
table is how the change and the event commit atomically. Today the relay delivers straight to HTTP; that is
the cheapest correct design at this size.

Costs we accept: polling every 500 ms (a partial-index scan, `outbox_pending_due_idx`, that is empty most of the
time); throughput bounded by batch size, poll interval and merchant latency (a tick drains up to 10 full batches);
the table grows until a purge job exists (follow-up).

### 7. Moving to Kafka or SQS later

The outbox stays; only what reads it changes.

1. **Relay publishes to the broker instead of HTTP.** Replace `MerchantDeliveryClient` with a producer. Kafka:
   topic `order-notifications`, key = `aggregate_id` (per-order ordering within a partition), header
   `ledgerline-event-id` = outbox id, idempotent producer. SQS: a FIFO queue with `MessageGroupId = aggregate_id`
   and `MessageDeduplicationId` = outbox id (SQS deduplicates within 5 minutes). "Delivered" then means
   "acknowledged by the broker".
2. **Or replace polling with change data capture.** Debezium reads `outbox` inserts from the PostgreSQL WAL (its
   outbox event router maps `aggregate_id`, `event_type` and `payload` to key, type and value). This gives lower
   latency and no polling, at the cost of running Kafka Connect.
3. **Merchant webhooks become a consumer** of the topic or queue, with the same signing, backoff and DEAD
   handling as today (on Kafka, a retry topic and a dead-letter topic instead of `next_attempt_at`).
4. **Keep the event id.** Consumers already deduplicate by `Ledgerline-Event-Id`; it stays the outbox id, so the
   migration does not change their contract.
5. **Migrate safely**: run the broker relay in shadow mode (publish without marking rows), compare, switch, then
   remove the HTTP relay.

### Consequences

- Good: an order change and its notification commit or roll back together (LL-I13). Notifications are not lost while
  the database survives.
- Neutral: delivery is at-least-once and unordered; this is documented for merchants and in AGENTS.md.
- Bad: a merchant that stays down for about 17 minutes produces DEAD rows, which need an operator to replay them.
  `ledgerline_outbox_dead_total`, `ledgerline_outbox_pending` and `ledgerline_outbox_oldest_pending_age_seconds`
  are the signals to alert on (alert rules are not part of this change).
- The admin endpoints need HTTP Basic (`VIEWER` lists, `ADMIN` replays), and the audit actor is the authenticated
  user name. (When this ADR was first written they were unauthenticated with a self-declared `X-Operator` header;
  that was replaced before release.) HTTP Basic against configured BCrypt hashes is adequate for a demo, not for
  a production operator console, which would use SSO.
- Bad: the relay supports one merchant URL. Per-merchant endpoints and secrets would need a merchant table.

### Confirmation

`OutboxRelayIT` (LL-I13 to LL-I18, LL-I20), `BackoffPolicyTest` (LL-U14), the `merchant-down` and `merchant-slow`
chaos scenarios (LL-I21) and the end-to-end dead-letter replay (LL-E04).

## Pros and Cons of the Options

### Dual-write

* Good, because simplest to write.
* Bad, because every ordering has a failure window: commit-then-POST loses notifications on a crash, POST-then-commit
  announces changes that never happened (table above).

### POST inside the transaction

* Bad, because a slow merchant holds row locks and a pooled connection for seconds, and both dual-write failures
  remain.

### Distributed transaction (2PC/XA)

* Bad, because an HTTP endpoint is not an XA resource; it does not exist for this pair.

### Transactional outbox

* Good, because atomic with the change by construction (one local transaction).
* Good, because the outbox doubles as an operator view of what failed (`/api/v1/admin/outbox`).
* Bad, because delivery becomes asynchronous and at-least-once, so receivers must deduplicate.
* Bad, because the table grows until a purge job exists.

## More Information

* Inbound counterpart (inbox and signatures): [ADR 0002](0002-webhook-signature-and-inbox.md).
* Relay mechanics and the broker question: [ADR 0004](0004-postgres-skip-locked-over-a-broker.md).

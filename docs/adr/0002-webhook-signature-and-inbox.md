# ADR 0002: Webhook signatures and the inbox

- Status: accepted
- Date: 2026-11-23
- Context: Ledgerline (SpecShield Labs internal demonstration, synthetic data only)

## Context

MockPay, our payment-provider simulator, reports payment progress by POSTing signed webhooks to
`/webhooks/mockpay`. Like any real provider it delivers **at least once**: it retries on 5xx and timeouts, so the
same event can arrive many times, concurrently, and out of order. The endpoint is reachable without
authentication, so anyone can send it anything. We need to (1) accept only genuine events, (2) apply each event
exactly once, (3) never lose an event, and (4) tell the provider when to stop retrying.

## Decisions

### 1. Stripe-style HMAC signature, verified on the raw bytes

`MockPay-Signature: t=<unix seconds>,v1=<hex>`, where hex is `HMAC-SHA256(secret, t + "." + rawBody)`.

- **Raw bytes.** The controller takes `@RequestBody byte[]`, so no JSON parser runs before verification, and the
  bytes we verify are exactly the bytes we decode. Re-serialising parsed JSON would change whitespace or key
  order and break the MAC.
- **The timestamp is signed.** An attacker cannot refresh `t` on a captured delivery without the secret.
- **Defensive parsing.** The header is bounded (1 KiB, 10 elements), needs exactly one numeric `t` and at least
  one 64-hex-digit `v1`, and ignores unknown schemes such as `v0`, so the provider can add schemes later. Every
  failure is a checked `InvalidSignatureException` with a reason; no input produces an unchecked exception
  (LL-U13).
- **Rotation.** The receiver accepts any configured secret, and MockPay signs with every configured secret (one
  `v1` each). To rotate: add the new secret to the receiver, then to MockPay, then remove the old one from both.
  At no point does a valid delivery fail.

### 2. Constant-time comparison

MACs are compared with `MessageDigest.isEqual`. `String.equals` and `Arrays.equals` stop at the first differing
byte, so their running time leaks how much of a guessed MAC is right. Over many requests an attacker can recover
a valid MAC byte by byte. `isEqual` examines every byte whatever the input. LL-U12 enforces this in the bytecode
with ArchUnit: the verifier must call `isEqual`, must not call `Arrays.equals` or `Arrays.compare` on byte
arrays, and must never format a MAC as hex, which is the first step of any string comparison.

### 3. A 300-second tolerance window

A delivery is rejected if `|now - t| > 300 s` (our `Clock` is injected, so the boundary is tested exactly:
LL-U11). Without a window, a captured delivery could be replayed forever. With one, replay is only possible for
five minutes, and within those minutes the inbox turns a replay into a harmless duplicate. 300 s is the Stripe
default: wide enough for clock skew and retry delays, narrow enough to bound replay. The window applies in both
directions, so a provider clock running fast is caught too.

### 4. A PostgreSQL inbox, not Redis

Every verified event is inserted into `order_events_inbox`, unique on `(provider, provider_event_id)`, **in the
same transaction** as the order change, audit rows and outbox row. We chose this over a Redis "seen" set because:

- **Atomicity.** The dedupe record and the state change commit or roll back together. With Redis there are two
  stores and no shared transaction: mark-then-apply loses the event if the apply fails, and apply-then-mark
  applies it twice if the mark fails.
- **The unique index is the lock.** Under 50 concurrent deliveries of one event (LL-I08), PostgreSQL makes the
  second insert wait for the first transaction and then report the conflict. No distributed lock and no TTL.
- **Durability and audit.** The inbox keeps every event's payload and outcome (APPLIED, DUPLICATE, STALE,
  IGNORED, ORPHAN, REJECTED, FAILED) for the order timeline, investigations and reconciliation. A Redis key with
  a TTL forgets.
- **One less moving part** for a demo whose database is already PostgreSQL.

We use `INSERT ... ON CONFLICT DO NOTHING RETURNING id`, not a plain insert that throws. The unique constraint
still decides, but a violated constraint aborts a PostgreSQL transaction, and we must still write the DUPLICATE
audit row in it.

Events can also arrive **out of order** (a retried PAYMENT_INITIATED after PAYMENT_SUCCEEDED). The state machine
treats a lower sequence as STALE, so applying events in arrival order could make an earlier event STALE and lose
it (ADR context for LL-P01). The receiver therefore locks the order (`SELECT ... FOR UPDATE`) and hands **all** of
its pending inbox events to `OrderEventSequencer`, which applies them in sequence order and leaves events after a
gap pending until the missing one arrives.

### 5. 200 for duplicates

A duplicate is answered `200 {"received":true,"duplicate":true}`, not 409.

- A provider retries until it gets a 2xx. The duplicate exists because our earlier 2xx was lost or late; the
  event *was* processed, and the honest answer is "received". A 4xx would make a well-behaved provider retry,
  alert or disable the endpoint, all for an event we already hold.
- The work is idempotent: the second delivery changes nothing (LL-I07, LL-I08), and is visible in the
  `ledgerline_webhook_duplicates_total` metric and as a `DUPLICATE` audit row.

The other status codes follow the same rule, "will retrying help?": invalid signature or malformed event is 400
(retrying the same bytes cannot succeed); an unexpected failure is 500 after a full rollback, including the inbox
row, so the retry is processed as new (LL-I12); unknown event types and unknown orders are 200 (IGNORED, or
ORPHAN with retries for an hour, LL-I11).

## Consequences

- **Rejected deliveries are stored safely.** A rejected delivery gets an inbox row with a synthetic id
  (`rejected_<uuid>`) and only the body's SHA-256 and length, never the untrusted event id. Otherwise a forger
  could claim the id of a future genuine event and make it look like a duplicate. The secret and the signature
  are never logged or echoed (LL-I09).
- **Abuse can grow the inbox.** Each rejected request writes a row. Rate limiting the endpoint is future work.
- **Gaps need watching.** Events behind a missing sequence number wait indefinitely; they should feed a "stuck
  events" alert.
- **One known window remains.** If the database commits but our 200 is lost, the provider retries and gets a
  duplicate 200. That is correct and costs nothing.
- **Reconciliation catches drift.** Every 5 minutes, MockPay's own ledger is compared with order statuses, and
  differences become `RECONCILIATION_MISMATCH` audit rows (LL-I19). It catches lost webhooks and manual edits.

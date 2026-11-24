---
status: accepted
date: 2026-11-24
decision-makers: SpecShield Labs engineering
---

# ADR 0005: Idempotency-Key design for state-changing requests

## Context and Problem Statement

Clients retry. A mobile app loses its connection after `POST /api/v1/orders` is sent; a load balancer times out
while `POST /pay` is still running; a user double-clicks. Without protection each retry creates another order or
another payment request. How does a client make a state-changing request safe to retry, and what does the server
answer in each situation?

This record was written when the code already existed; it records the reasoning from the orders API pull request.

## Decision Drivers

* A retry of the same request must never execute twice, even when the retries are concurrent (LL-I04).
* A retry must get the *same* answer as the first request, so a client can treat it as the original.
* Reusing a key for a different request is a client bug and must be loud, not silently replayed.
* A server-side failure must not lock the client out of retrying.
* Keep controllers free of the mechanism (AGENTS.md: handled by a filter and a service).

## Considered Options

* Client-supplied `Idempotency-Key` header, stored in PostgreSQL with a request fingerprint and the full response
  (Stripe style)
* Natural keys only (for example a unique `merchantRef`)
* Server-issued request tokens (client fetches a token, then submits with it)
* No server-side support; clients check before retrying

## Decision Outcome

Chosen option: "Client-supplied `Idempotency-Key` stored in PostgreSQL with a fingerprint and the full response",
because it covers every state-changing endpoint uniformly, needs one round trip, and is the convention payment
APIs already use, so integrators recognise it.

Design (`web.IdempotencyFilter`, `application.IdempotencyService`, table `idempotency_keys`):

1. **Required** on every state-changing `POST`; 8 to 128 characters of `[A-Za-z0-9_-]`. Missing or malformed is
   `400` problem+json (LL-I01).
2. **Fingerprint.** The key is bound to SHA-256 of method, path and the canonical JSON body (keys sorted, numbers
   as exact decimals, whitespace ignored), so a re-serialised but equal body still matches.
3. **Claim first, in its own transaction:** `INSERT ... IN_PROGRESS` keyed by the idempotency key. The primary key
   is the lock: of N concurrent requests exactly one inserts; the others see the row.
4. **Decide** for an existing row: different fingerprint is `422 idempotency-key-reuse` (LL-I03); still
   `IN_PROGRESS` is `409 idempotency-request-in-progress` with `Retry-After: 1`; completed is a **byte-for-byte
   replay** of the stored status, body, `Content-Type` and `Location`, plus `Idempotent-Replayed: true` (LL-I02).
5. **Complete.** 2xx and 4xx responses are stored (a 4xx is a deterministic answer to that request). A 5xx or an
   exception **deletes** the claim, so the client's retry executes again.
6. **Expire.** Keys live 24 hours; an hourly job deletes expired rows (LL-I05). An expired key is treated as new.

### Consequences

* Good, because 10 concurrent POSTs with one key create one order, and every response is the same `201` or a
  `409` (LL-I04).
* Good, because replays are exact, so clients and proxies cannot tell a replay from the original except by the
  header.
* Good, because the mechanism is outside controllers; a new endpoint opts in through the filter.
* Bad, because of a known window: the claim, the business transaction and storing the response are three
  transactions. If the process dies after the business commit and before the response is stored, the key stays
  `IN_PROGRESS` (409) until it expires, and a retry after expiry would execute again. Closing it means storing the
  response in the business transaction, which couples every use case to the idempotency table; not worth it for a
  demo, and documented in the README.
* Bad, because keys are global, not scoped per client: the API has no client identity (it is a public demo). With
  authentication, the key's scope would become `(client, key)`.
* Neutral: the stored response bodies are synthetic order data, deleted after 24 hours.

### Confirmation

`IdempotencyIT` (LL-I01 to LL-I05), `IdempotencyServiceTest` and `IdempotencyServiceFlowTest` (unit, under the PIT
mutation gate), and `ProblemDetailsIT` (LL-C02) for the shape of the 409 and 422 problems.

## Pros and Cons of the Options

### Client-supplied key with fingerprint and stored response

* Good, because uniform across endpoints, one round trip, exact replay.
* Good, because the fingerprint turns key reuse into an explicit error.
* Bad, because a table write per request and a cleanup job.

### Natural keys only

* Good, because no extra table.
* Bad, because not every command has one (`/pay`, `/cancel`), and a unique violation does not give the client the
  original response.

### Server-issued request tokens

* Good, because the server controls key quality.
* Bad, because two round trips per command and the same storage problem.

### No server support

* Bad, because "check then retry" races with the original request; it cannot be made correct on the client.

## More Information

* Inbound webhooks use a different mechanism, the inbox keyed by the provider's event id:
  [ADR 0002](0002-webhook-signature-and-inbox.md).
* The idempotency key also makes the public chaos API safe to retry: the same key returns the same scenario run.

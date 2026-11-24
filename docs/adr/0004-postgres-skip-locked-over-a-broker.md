---
status: accepted
date: 2026-11-24
decision-makers: SpecShield Labs engineering
---

# ADR 0004: PostgreSQL `FOR UPDATE SKIP LOCKED` instead of a message broker

## Context and Problem Statement

[ADR 0003](0003-transactional-outbox.md) puts every merchant notification in an `outbox` table in the same
transaction as the order change. Something must then read committed rows and deliver them, with several workers
(threads or application instances) at once, without delivering a row twice in the normal case and without a
leader election. Should that be a message broker (Kafka, RabbitMQ, SQS), or PostgreSQL itself?

This record was written when the code already existed; it records the reasoning from the outbox pull request.

## Decision Drivers

* Correctness: a row is claimed by one worker at a time; a crashed worker's rows come back.
* Operational cost: what a small team must run, secure, monitor and pay for.
* The outbox table is needed either way: publishing to a broker from the request transaction is dual-write again.
* Expected load: one consumer type (merchant HTTP endpoints), demo volumes.
* A clear path to a broker if load or the number of consumers grows.

## Considered Options

* PostgreSQL polling relay with `SELECT ... FOR UPDATE SKIP LOCKED`
* Outbox plus a broker (Kafka, RabbitMQ or SQS), with the relay publishing to it
* Change data capture (Debezium reading the WAL) into Kafka
* PostgreSQL `LISTEN/NOTIFY`
* An advisory-lock or leader-elected single relay

## Decision Outcome

Chosen option: "PostgreSQL polling relay with `FOR UPDATE SKIP LOCKED`", because it is the cheapest correct
design at this size: no new infrastructure, any number of workers with no coordination, and the broker options
would still need the outbox table and its relay.

How it works (`outbox.OutboxDispatcher`, `persistence.JdbcOutboxStore`):

```sql
UPDATE outbox SET status = 'IN_FLIGHT', claimed_at = :now
 WHERE id IN (SELECT id FROM outbox
               WHERE status = 'PENDING' AND next_attempt_at <= :now
               ORDER BY id
               LIMIT :limit
               FOR UPDATE SKIP LOCKED)
RETURNING ...
```

Every 500 ms each dispatcher claims up to 20 due rows in one short transaction, delivers them on virtual threads
outside any transaction, and records each result with an update conditional on its own claim
(`WHERE id = ? AND status = 'IN_FLIGHT' AND claimed_at = ?`). Rows another worker has locked are skipped, not
waited for. A watchdog returns rows `IN_FLIGHT` for more than 60 s to `PENDING`. The due-row scan uses the partial
index `outbox_pending_due_idx` (on `next_attempt_at` where `status = 'PENDING'`), which is empty most of the time.

### Consequences

* Good, because nothing new to run: the service and its notifications depend on one PostgreSQL database.
* Good, because horizontal scale-out needs no configuration: two dispatchers over 200 rows delivered each row
  exactly once (LL-I17).
* Good, because claims, retries and dead letters are plain SQL rows an operator can query.
* Bad, because latency includes up to one poll interval (500 ms).
* Bad, because throughput is bounded by batch size, poll interval and merchant latency, and polling load lands on
  the primary database. We have not load-tested the relay on its own; the k6 test (LL-N01) measures the webhook
  path.
* Bad, because there is no fan-out: adding a second kind of consumer (analytics, search indexing) means another
  relay or moving to a broker.
* Neutral: delivery stays at-least-once and unordered, as with any broker; receivers deduplicate by
  `Ledgerline-Event-Id`.

### Confirmation

`OutboxRelayIT`: LL-I17 (two concurrent dispatchers, each row once), LL-I20 (watchdog release), LL-I15 (backoff then
DEAD). An ArchUnit rule in `ArchitectureTest` keeps the relay talking to the database only through
`application.OutboxStore`.

## Pros and Cons of the Options

### PostgreSQL `FOR UPDATE SKIP LOCKED`

* Good, because built into PostgreSQL since 9.5 and designed for exactly this queue pattern.
* Good, because no leader election, no consumer-group rebalancing.
* Bad, because polling, and the queue shares the database's capacity.

### Outbox plus a broker

* Good, because fan-out to many consumers, replay from a retained log (Kafka), managed scaling (SQS).
* Bad, because it does not remove the outbox or its relay; it adds a system to run and secure on top.
* Bad, because for one HTTP consumer it moves the problem without solving a new one.

### Change data capture (Debezium)

* Good, because no polling and low latency; the outbox event router maps rows to Kafka records.
* Bad, because it needs Kafka and Kafka Connect, logical replication slots and their monitoring (a stuck slot
  retains WAL and can fill the disk).

### `LISTEN/NOTIFY`

* Good, because push instead of poll.
* Bad, because notifications are lost while no listener is connected, so a polling fallback is still needed;
  it is a latency optimisation, not a queue.

### Single leader-elected relay

* Good, because simple ordering.
* Bad, because one worker's throughput, plus leader election and failover to get right.

## More Information

* When to revisit: a second consumer type, sustained delivery volume where polling shows up in database load, or a
  need for per-order ordering. The migration plan (relay publishes to Kafka or SQS, or Debezium, with the outbox id
  kept as the event id) is in [ADR 0003, section 7](0003-transactional-outbox.md#7-moving-to-kafka-or-sqs-later).
* The same pattern is the first step in the README's "At 10x scale" section (partitioning the outbox).

---
status: accepted
date: 2026-11-24
decision-makers: SpecShield Labs engineering
---

# ADR 0001: Record architecture decisions

## Context and Problem Statement

Ledgerline is small, but most of its value is in *why* it is built the way it is: why an inbox and not a Redis set,
why an outbox and not a broker, why Testcontainers and not H2. That reasoning lives in pull request threads and in
people's heads, where a new engineer, a reviewer or an AI agent cannot find it. How do we keep the reasons next to
the code?

## Decision Drivers

* A reader should understand a design choice without asking the person who made it.
* Decisions should be reviewable in the same pull request as the code that implements them.
* The format should be light enough that people actually write one.

## Considered Options

* Architecture Decision Records in the repository, in MADR format
* A wiki page or shared document per decision
* Long comments in the code
* No written record (pull request descriptions only)

## Decision Outcome

Chosen option: "Architecture Decision Records in the repository, in MADR format", because they are versioned and
reviewed with the code, render on GitHub, and MADR gives every record the same shape (problem, options, outcome,
consequences) without much ceremony.

Rules:

* One file per decision in `docs/adr/`, named `NNNN-short-title.md`, numbered in order and never renumbered.
* Use the [MADR 4](https://adr.github.io/madr/) sections: context and problem statement, decision drivers,
  considered options, decision outcome with consequences, pros and cons of the options.
* A record is written in the pull request that makes the decision. Once accepted it is not rewritten; a later
  decision that changes it gets a new record whose status says `supersedes ADR NNNN`, and the old one's status
  becomes `superseded by ADR MMMM`. Small factual corrections (a renamed class, a stale link) are fine.
* Any number in an ADR (latency, counts, scores) must be reproducible from this repository, as for the README
  ([AGENTS.md](../../AGENTS.md), honesty rule).

### Consequences

* Good, because the reasoning behind the guarantees in the README is one click from the code that implements them.
* Good, because reviewers can challenge a decision in the same diff that introduces it.
* Bad, because records can drift from the code. We accept small factual corrections and point to tests (by
  catalogue ID) rather than restating behaviour, so drift shows up as a failing or missing test.

### Confirmation

Pull request review: a change to a guarantee listed in the README without an ADR (new or superseding) is sent back.

## Pros and Cons of the Options

### ADRs in the repository (MADR)

* Good, because versioned, diffable and reviewed with the code.
* Good, because a standard template makes records quick to write and to read.
* Bad, because Markdown in a repository is less discoverable for non-engineers than a wiki.

### Wiki or shared document

* Good, because easy to edit and to comment on.
* Bad, because it is not versioned with the code and goes stale silently.

### Comments in the code

* Good, because they sit exactly where the decision applies.
* Bad, because cross-cutting decisions (outbox, testing strategy) have no single place in the code, and rejected
  options have nowhere to live.

### No written record

* Good, because free.
* Bad, because the reasoning is lost when people move on.

## More Information

Records so far:

| ADR | Decision |
|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions (this record) |
| [0002](0002-webhook-signature-and-inbox.md) | HMAC webhook signatures and a PostgreSQL inbox |
| [0003](0003-transactional-outbox.md) | Transactional outbox for merchant notifications |
| [0004](0004-postgres-skip-locked-over-a-broker.md) | PostgreSQL `FOR UPDATE SKIP LOCKED` instead of a message broker |
| [0005](0005-idempotency-key-design.md) | Idempotency-Key design for state-changing requests |
| [0006](0006-testing-strategy.md) | Testing strategy: Testcontainers, property-based and mutation testing |

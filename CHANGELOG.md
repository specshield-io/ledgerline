# Changelog

All notable changes to Ledgerline are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The public contract versioned here is the HTTP API in
[`docs/openapi.json`](docs/openapi.json) and the webhook and notification formats in [docs/reference.md](docs/reference.md).

## [Unreleased]

### Security

- The webhook signature verifier feeds the timestamp prefix and the raw body to the HMAC separately instead of
  concatenating them into a buffer sized from the request, so no array length is computed from user input
  (CodeQL `java/tainted-arithmetic`, alert #11). Signatures and behaviour are unchanged.

## [1.0.0] - 2026-11-24

First tagged release of the SpecShield Labs internal demonstration. Synthetic data only; not a production payment
system.

### Added

- Order domain with a payment state machine, money as `long` minor units in USD, UTC `Instant`s, and the
  PostgreSQL 17 schema as Flyway migrations (#4).
- Public orders REST API (`/api/v1/orders`) with Stripe-style `Idempotency-Key` handling: request fingerprinting,
  byte-exact replay, `409` while in progress, `422` on key reuse; RFC 9457 problem+json for every error (#5).
- MockPay payment-provider simulator sending real HMAC-signed webhooks, and a hardened receiver: raw-byte
  verification, constant-time comparison, 300 s tolerance, PostgreSQL inbox deduplication, sequence-ordered event
  application, orphan reprocessing and reconciliation (#6).
- Transactional outbox with a `FOR UPDATE SKIP LOCKED` relay, signed merchant notifications, full-jitter backoff,
  dead letters, admin replay and a crash watchdog (#7).
- Public chaos API with eight failure scenarios, HTTP Basic admin roles (`VIEWER`, `ADMIN`) with BCrypt hashes,
  per-IP rate limiting with a trusted-proxy rule, and a nightly demo data reset under ShedLock (#8).
- React operations console with live order timelines, outbox view, metrics strip and Playwright end-to-end tests
  including axe accessibility checks (#9).
- Engineering evidence: OpenTelemetry tracing and ECS JSON logs, JaCoCo and PIT gates, k6 load test, OWASP ZAP,
  Trivy, CodeQL and OpenSSF Scorecard, and `evidence/latest.json` published to GitHub Pages by CI (#15).
- Documentation for engineering leaders: a rewritten README (guarantees mapped to test IDs, architecture and
  duplicate-delivery diagrams, failure scenarios, limitations, scaling plan, code tour), a recorded console tour,
  [docs/reference.md](docs/reference.md), [docs/runbook.md](docs/runbook.md), [CONTRIBUTING.md](CONTRIBUTING.md) and
  this changelog.
- Architecture decision records 0001, 0004, 0005 and 0006 in MADR format; 0002 and 0003 restructured to MADR.
- CI: `fresh-clone` job (LL-N06) that starts the stack from a clean checkout and must be healthy within 180 s, and a
  lychee link check over all Markdown.
- Release workflow: pushing a `v*` tag creates the GitHub release with its CHANGELOG section and attaches the
  CycloneDX SBOM (`bom.json`).

### Known limitations

- See [README, section 7](README.md#7-security-notes-and-known-limitations): in-memory per-instance rate limits,
  single region, HTTP Basic admin, MockPay is a simulator, and one open high-severity CodeQL alert awaiting triage.

[Unreleased]: https://github.com/specshield-io/ledgerline/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/specshield-io/ledgerline/releases/tag/v1.0.0

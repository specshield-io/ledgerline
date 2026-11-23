-- Ledgerline V1: initial schema. PostgreSQL 17.
-- Money is bigint minor units (cents) with currency 'USD'. Every timestamp is timestamptz (UTC).

CREATE TABLE orders (
    id                     uuid         PRIMARY KEY,
    merchant_ref           varchar(64)  NOT NULL CHECK (length(btrim(merchant_ref)) > 0),
    amount_minor           bigint       NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    currency               varchar(3)   NOT NULL CHECK (currency = 'USD'),
    status                 varchar(32)  NOT NULL CHECK (status IN ('CREATED', 'PAYMENT_PENDING', 'PAID',
                               'PAYMENT_FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'CANCELLED')),
    refunded_minor         bigint       NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
    last_event_sequence    bigint       NOT NULL DEFAULT 0 CHECK (last_event_sequence >= 0),
    version                bigint       NOT NULL DEFAULT 0,
    created_at             timestamptz  NOT NULL,
    updated_at             timestamptz  NOT NULL,
    last_provider_event_at timestamptz,
    CONSTRAINT orders_refund_within_amount CHECK (refunded_minor <= amount_minor),
    CONSTRAINT orders_refund_matches_status CHECK (
        CASE status
            WHEN 'PARTIALLY_REFUNDED' THEN refunded_minor > 0 AND refunded_minor < amount_minor
            WHEN 'REFUNDED'           THEN refunded_minor = amount_minor
            ELSE refunded_minor = 0
        END)
);

COMMENT ON COLUMN orders.last_event_sequence IS
    'Provider sequence of the last applied payment event; 0 = none. Events at or below it are STALE.';

-- Raw provider events, stored before processing. UNIQUE(provider, provider_event_id) is the dedupe guard.
CREATE TABLE order_events_inbox (
    id                bigserial    PRIMARY KEY,
    provider          varchar(64)  NOT NULL,
    provider_event_id varchar(255) NOT NULL,
    payload           jsonb        NOT NULL,
    signature_valid   boolean      NOT NULL,
    received_at       timestamptz  NOT NULL,
    processed_at      timestamptz,
    outcome           varchar(16)  CHECK (outcome IN ('APPLIED', 'DUPLICATE', 'STALE', 'IGNORED', 'ORPHAN',
                                                      'REJECTED', 'FAILED')),
    CONSTRAINT order_events_inbox_provider_event_uk UNIQUE (provider, provider_event_id),
    CONSTRAINT order_events_inbox_processed_has_outcome CHECK ((processed_at IS NULL) = (outcome IS NULL))
);

-- order_id is nullable: some audit entries (rejected webhooks, orphans) have no order.
CREATE TABLE audit_log (
    id             bigserial    PRIMARY KEY,
    order_id       uuid,
    kind           varchar(64)  NOT NULL,
    detail         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    actor          varchar(128) NOT NULL,
    correlation_id varchar(128),
    created_at     timestamptz  NOT NULL
);

CREATE INDEX audit_log_order_created_idx ON audit_log (order_id, created_at);

CREATE TABLE idempotency_keys (
    key             varchar(255) PRIMARY KEY,
    request_hash    varchar(128) NOT NULL,
    response_status integer      CHECK (response_status BETWEEN 100 AND 599),
    response_body   jsonb,
    state           varchar(16)  NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    created_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    CONSTRAINT idempotency_keys_completed_has_status CHECK (state <> 'COMPLETED' OR response_status IS NOT NULL),
    CONSTRAINT idempotency_keys_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE TABLE outbox (
    id              bigserial    PRIMARY KEY,
    aggregate_id    uuid         NOT NULL,
    event_type      varchar(64)  NOT NULL,
    payload         jsonb        NOT NULL,
    status          varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'DEAD')),
    attempts        integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz  NOT NULL,
    last_error      text,
    created_at      timestamptz  NOT NULL,
    delivered_at    timestamptz,
    CONSTRAINT outbox_delivered_has_timestamp CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

-- The relay polls only due PENDING rows; the partial index keeps that scan small.
CREATE INDEX outbox_pending_due_idx ON outbox (next_attempt_at) WHERE status = 'PENDING';

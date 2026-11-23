-- Ledgerline V3: MockPay's own ledger (the simulated provider's record) and webhook processing indexes.

CREATE TABLE mockpay_ledger (
    payment_id    varchar(64)  PRIMARY KEY,
    order_id      uuid         NOT NULL,
    amount_minor  bigint       NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    currency      varchar(3)   NOT NULL CHECK (currency = 'USD'),
    status        varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    last_sequence bigint       NOT NULL CHECK (last_sequence >= 1),
    created_at    timestamptz  NOT NULL,
    settled_at    timestamptz,
    CONSTRAINT mockpay_ledger_settled_when_final CHECK ((status = 'PENDING') = (settled_at IS NULL))
);

CREATE INDEX mockpay_ledger_order_created_idx ON mockpay_ledger (order_id, created_at DESC);
CREATE INDEX mockpay_ledger_settled_idx ON mockpay_ledger (settled_at) WHERE status <> 'PENDING';

-- Events waiting to be applied in sequence order (outcome not yet decided).
CREATE INDEX order_events_inbox_pending_idx ON order_events_inbox (order_id, id)
    WHERE outcome IS NULL AND signature_valid;

-- ORPHAN events (order unknown when received) are retried for a limited window.
CREATE INDEX order_events_inbox_orphan_idx ON order_events_inbox (received_at) WHERE outcome = 'ORPHAN';

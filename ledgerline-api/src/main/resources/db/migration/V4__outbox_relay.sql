-- Ledgerline V4: outbox relay support.
-- claimed_at records when a dispatcher took the row (IN_FLIGHT). The watchdog returns rows claimed too long ago to
-- PENDING, and a dispatcher only records its result while the row still carries its own claim.

ALTER TABLE outbox ADD COLUMN claimed_at timestamptz;

ALTER TABLE outbox ADD CONSTRAINT outbox_claimed_when_in_flight
    CHECK ((status = 'IN_FLIGHT') = (claimed_at IS NOT NULL));

ALTER TABLE outbox ADD CONSTRAINT outbox_last_error_bounded
    CHECK (last_error IS NULL OR length(last_error) <= 500);

-- Watchdog scan: IN_FLIGHT rows by claim time.
CREATE INDEX outbox_in_flight_claimed_idx ON outbox (claimed_at) WHERE status = 'IN_FLIGHT';

-- Admin listing by status, newest first, and the oldest-pending gauge.
CREATE INDEX outbox_status_id_idx ON outbox (status, id);

-- Ledgerline V2: support for the public orders API.

-- Idempotent replay must return the stored response byte for byte. jsonb normalises whitespace and key order,
-- so the body is kept as raw bytes, together with the headers a replay must reproduce.
ALTER TABLE idempotency_keys
    ALTER COLUMN response_body TYPE bytea USING convert_to(response_body::text, 'UTF8');
ALTER TABLE idempotency_keys
    ADD COLUMN response_content_type varchar(255),
    ADD COLUMN response_location     varchar(2048);

-- The cleanup job deletes by expiry.
CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);

-- Inbox rows are linked to their order once the payload is parsed (NULL for orphans), for the order timeline.
ALTER TABLE order_events_inbox ADD COLUMN order_id uuid;
CREATE INDEX order_events_inbox_order_received_idx ON order_events_inbox (order_id, received_at);

-- GET /api/v1/orders lists newest first, optionally filtered by status.
CREATE INDEX orders_created_idx ON orders (created_at DESC, id DESC);
CREATE INDEX orders_status_created_idx ON orders (status, created_at DESC, id DESC);

-- Ledgerline V6: the operations console reads an order's notifications (GET /api/v1/orders/{id}/deliveries) every
-- second while a scenario runs.
CREATE INDEX outbox_aggregate_idx ON outbox (aggregate_id, id);

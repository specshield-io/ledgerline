-- LL-N01 load test data: synthetic CREATED orders whose ids the k6 script can compute (md5 of 'load-order-<n>').
-- Idempotent: running it again adds nothing.
INSERT INTO orders (id, merchant_ref, amount_minor, currency, status, refunded_minor, last_event_sequence, version,
                    created_at, updated_at)
SELECT md5('load-order-' || n)::uuid, 'load-' || n, 2599, 'USD', 'CREATED', 0, 0, 0, now(), now()
  FROM generate_series(0, :orders - 1) AS n
ON CONFLICT (id) DO NOTHING;

package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.domain.OrderStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory port implementations for unit tests. Not thread safe. */
final class InMemory {

    private InMemory() {}

    /** An order in any status, consistent with the domain invariants (refunds only for refund statuses). */
    static Order order(UUID id, OrderStatus status, Instant at) {
        long refunded = switch (status) {
            case PARTIALLY_REFUNDED -> 1000;
            case REFUNDED -> 2599;
            default -> 0;
        };
        long sequence = status == OrderStatus.CREATED || status == OrderStatus.CANCELLED ? 0 : 2;
        return new Order(
                id, "synthetic", 2599, "USD", status, refunded, sequence, 0, at, at, sequence == 0 ? null : at);
    }

    static final class Orders implements OrderStore {
        final Map<UUID, Order> rows = new LinkedHashMap<>();
        int updates;

        @Override
        public Order insert(Order order) {
            rows.put(order.id(), order);
            return order;
        }

        @Override
        public Order update(Order order) {
            updates++;
            rows.put(order.id(), order);
            return order;
        }

        @Override
        public Optional<Order> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Order> findByIdForUpdate(UUID id) {
            return findById(id);
        }
    }

    static final class Audit implements AuditTrail {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void append(AuditRecord record) {
            records.add(record);
        }

        @Override
        public List<AuditRecord> forOrder(UUID orderId) {
            return records.stream().filter(r -> orderId.equals(r.orderId())).toList();
        }

        List<String> kinds() {
            return records.stream().map(AuditRecord::kind).toList();
        }
    }

    record OutboxRow(UUID aggregateId, String eventType, String payloadJson, Instant at) {}

    static final class OutboxRows implements Outbox {
        final List<OutboxRow> rows = new ArrayList<>();

        @Override
        public void append(UUID aggregateId, String eventType, String payloadJson, Instant now) {
            rows.add(new OutboxRow(aggregateId, eventType, payloadJson, now));
        }
    }

    /** Inbox with the same dedupe and pending semantics as the SQL implementation. */
    static final class Inbox implements PaymentInbox {
        record Row(
                long id,
                String provider,
                String eventId,
                UUID orderId,
                String payload,
                boolean valid,
                Instant receivedAt,
                String outcome) {}

        final List<Row> rows = new ArrayList<>();

        @Override
        public Optional<Long> insertIfAbsent(
                String provider, String providerEventId, UUID orderId, String payloadJson, Instant receivedAt) {
            if (rows.stream().anyMatch(r -> r.provider.equals(provider) && r.eventId.equals(providerEventId))) {
                return Optional.empty();
            }
            long id = rows.size() + 1;
            rows.add(new Row(id, provider, providerEventId, orderId, payloadJson, true, receivedAt, null));
            return Optional.of(id);
        }

        @Override
        public void insertRejected(String provider, String syntheticEventId, String detailJson, Instant receivedAt) {
            rows.add(new Row(
                    rows.size() + 1, provider, syntheticEventId, null, detailJson, false, receivedAt, "REJECTED"));
        }

        @Override
        public List<PendingEvent> pendingForOrder(UUID orderId) {
            return rows.stream()
                    .filter(r -> orderId.equals(r.orderId) && r.outcome == null && r.valid)
                    .map(r -> new PendingEvent(r.id, r.eventId, r.payload))
                    .toList();
        }

        @Override
        public void markProcessed(long id, String outcome, Instant processedAt) {
            set(id, outcome);
        }

        @Override
        public List<UUID> orphanOrderIds(Instant since) {
            return rows.stream()
                    .filter(r -> "ORPHAN".equals(r.outcome) && !r.receivedAt.isBefore(since) && r.orderId != null)
                    .map(Row::orderId)
                    .distinct()
                    .toList();
        }

        @Override
        public int reopenOrphans(UUID orderId, Instant since) {
            int reopened = 0;
            for (Row r : List.copyOf(rows)) {
                if (orderId.equals(r.orderId) && "ORPHAN".equals(r.outcome) && !r.receivedAt.isBefore(since)) {
                    set(r.id, null);
                    reopened++;
                }
            }
            return reopened;
        }

        String outcome(String eventId) {
            return rows.stream()
                    .filter(r -> r.eventId.equals(eventId))
                    .findFirst()
                    .orElseThrow()
                    .outcome;
        }

        private void set(long id, String outcome) {
            int index = (int) id - 1;
            Row r = rows.get(index);
            rows.set(index, new Row(r.id, r.provider, r.eventId, r.orderId, r.payload, r.valid, r.receivedAt, outcome));
        }
    }
}

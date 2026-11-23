package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.application.PaymentInbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Inbox access with plain SQL: {@code INSERT ... ON CONFLICT DO NOTHING RETURNING id} keeps the transaction usable
 * when the unique constraint fires, which a JPA insert followed by a constraint violation would not (PostgreSQL
 * aborts the transaction). Runs in the caller's transaction (JpaTransactionManager shares the connection).
 */
@Repository
public class JdbcPaymentInbox implements PaymentInbox {

    private final JdbcClient jdbc;

    JdbcPaymentInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> insertIfAbsent(
            String provider, String providerEventId, UUID orderId, String payloadJson, Instant receivedAt) {
        return jdbc.sql("""
                        INSERT INTO order_events_inbox (provider, provider_event_id, order_id, payload, signature_valid, received_at)
                        VALUES (:provider, :eventId, :orderId, CAST(:payload AS jsonb), true, :receivedAt)
                        ON CONFLICT (provider, provider_event_id) DO NOTHING
                        RETURNING id
                        """)
                .param("provider", provider)
                .param("eventId", providerEventId)
                .param("orderId", orderId)
                .param("payload", payloadJson)
                .param("receivedAt", Timestamp.from(receivedAt))
                .query(Long.class)
                .optional();
    }

    @Override
    public void insertRejected(String provider, String syntheticEventId, String detailJson, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO order_events_inbox
                            (provider, provider_event_id, payload, signature_valid, received_at, processed_at, outcome)
                        VALUES (:provider, :eventId, CAST(:payload AS jsonb), false, :at, :at, 'REJECTED')
                        """)
                .param("provider", provider)
                .param("eventId", syntheticEventId)
                .param("payload", detailJson)
                .param("at", Timestamp.from(receivedAt))
                .update();
    }

    @Override
    public List<PendingEvent> pendingForOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, provider_event_id, payload::text AS payload
                          FROM order_events_inbox
                         WHERE order_id = :orderId AND outcome IS NULL AND signature_valid
                         ORDER BY id
                        """)
                .param("orderId", orderId)
                .query((rs, n) ->
                        new PendingEvent(rs.getLong("id"), rs.getString("provider_event_id"), rs.getString("payload")))
                .list();
    }

    @Override
    public void markProcessed(long id, String outcome, Instant processedAt) {
        jdbc.sql("UPDATE order_events_inbox SET outcome = :outcome, processed_at = :at WHERE id = :id")
                .param("outcome", outcome)
                .param("at", Timestamp.from(processedAt))
                .param("id", id)
                .update();
    }

    @Override
    public List<UUID> orphanOrderIds(Instant since) {
        return jdbc.sql("""
                        SELECT DISTINCT order_id FROM order_events_inbox
                         WHERE outcome = 'ORPHAN' AND received_at >= :since AND order_id IS NOT NULL
                        """)
                .param("since", Timestamp.from(since))
                .query(UUID.class)
                .list();
    }

    @Override
    public int reopenOrphans(UUID orderId, Instant since) {
        return jdbc.sql("""
                        UPDATE order_events_inbox SET outcome = NULL, processed_at = NULL
                         WHERE order_id = :orderId AND outcome = 'ORPHAN' AND received_at >= :since
                        """)
                .param("orderId", orderId)
                .param("since", Timestamp.from(since))
                .update();
    }
}

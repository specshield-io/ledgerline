package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for order_events_inbox. Joins the caller's transaction. */
public interface PaymentInbox {

    /**
     * INSERT ... ON CONFLICT (provider, provider_event_id) DO NOTHING. Returns the new row id, or empty if the event
     * is already stored (a duplicate). Under concurrency the unique index serialises inserts: a second transaction
     * waits for the first and then sees the conflict.
     */
    Optional<Long> insertIfAbsent(
            String provider, String providerEventId, UUID orderId, String payloadJson, Instant receivedAt);

    /** Stores a rejected delivery (signature failed) as already processed with outcome REJECTED. */
    void insertRejected(String provider, String syntheticEventId, String detailJson, Instant receivedAt);

    /** Signature-valid rows of the order whose outcome is not decided yet, oldest first. */
    List<PendingEvent> pendingForOrder(UUID orderId);

    void markProcessed(long id, String outcome, Instant processedAt);

    /** Distinct order ids of ORPHAN rows received at or after {@code since}. */
    List<UUID> orphanOrderIds(Instant since);

    /** Turns the order's ORPHAN rows received at or after {@code since} back into pending rows. */
    int reopenOrphans(UUID orderId, Instant since);

    record PendingEvent(long id, String providerEventId, String payloadJson) {}
}

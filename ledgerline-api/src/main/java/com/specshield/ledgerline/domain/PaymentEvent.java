package com.specshield.ledgerline.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * An event reported by the payment provider about one order.
 *
 * @param providerEventId the provider's unique id for this event; the deduplication key
 * @param occurredAt when the provider says it happened (provider clock, not ours)
 * @param sequence the provider's per-order counter, strictly increasing, starting at 1
 */
public record PaymentEvent(
        String providerEventId,
        PaymentEventType type,
        UUID orderId,
        long amountMinor,
        Instant occurredAt,
        long sequence) {

    public PaymentEvent {
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new IllegalArgumentException("providerEventId must not be blank");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Order.requireValidAmount(amountMinor);
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1, was " + sequence);
        }
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public String toString() {
        return type + "(id=" + providerEventId + ", seq=" + sequence + ", amountMinor=" + amountMinor + ")";
    }
}

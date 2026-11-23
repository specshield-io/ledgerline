package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.application.OutboxStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "OrderDelivery", description = "A merchant notification about the order and its delivery state")
public record DeliveryResponse(
        @Schema(description = "Outbox id; sent to the merchant as Ledgerline-Event-Id")
        long id,

        @Schema(example = "order.paid") String eventType,
        OutboxStatus status,

        @Schema(description = "Delivery attempts made so far")
        int attempts,

        @Schema(description = "When the next attempt is due (PENDING)")
        Instant nextAttemptAt,

        @Schema(nullable = true, description = "Error of the last failed attempt")
        String lastError,

        Instant createdAt,
        @Schema(nullable = true) Instant deliveredAt) {

    static DeliveryResponse from(OutboxMessage m) {
        return new DeliveryResponse(
                m.id(),
                m.eventType(),
                m.status(),
                m.attempts(),
                m.nextAttemptAt(),
                m.lastError(),
                m.createdAt(),
                m.deliveredAt());
    }
}

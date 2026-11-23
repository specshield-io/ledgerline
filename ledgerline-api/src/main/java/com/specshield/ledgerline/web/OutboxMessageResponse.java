package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.application.OutboxStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Schema(name = "OutboxMessage", description = "A merchant notification in the transactional outbox")
public record OutboxMessageResponse(
        @Schema(description = "Outbox id; sent to the merchant as Ledgerline-Event-Id")
        long id,

        @Schema(description = "The order the notification is about")
        UUID aggregateId,

        @Schema(example = "order.paid") String eventType,

        @Schema(description = "Notification data", implementation = Object.class, types = "object")
        JsonNode payload,

        OutboxStatus status,

        @Schema(description = "Delivery attempts made so far")
        int attempts,

        @Schema(description = "When the next attempt is due (PENDING)")
        Instant nextAttemptAt,

        @Schema(nullable = true, description = "Error of the last failed attempt")
        String lastError,

        Instant createdAt,
        @Schema(nullable = true) Instant deliveredAt) {

    static OutboxMessageResponse from(OutboxMessage m, JsonMapper json) {
        return new OutboxMessageResponse(
                m.id(),
                m.aggregateId(),
                m.eventType(),
                json.readTree(m.payloadJson()),
                m.status(),
                m.attempts(),
                m.nextAttemptAt(),
                m.lastError(),
                m.createdAt(),
                m.deliveredAt());
    }
}

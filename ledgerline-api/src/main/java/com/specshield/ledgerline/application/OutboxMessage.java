package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the outbox.
 *
 * @param attempts delivery attempts made so far
 * @param claimedAt when a dispatcher claimed it; set only while IN_FLIGHT
 */
public record OutboxMessage(
        long id,
        UUID aggregateId,
        String eventType,
        String payloadJson,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant deliveredAt,
        Instant claimedAt) {}

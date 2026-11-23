package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for writing to the transactional outbox. {@link #append} joins the caller's transaction, so the row commits
 * or rolls back together with the state change it announces. Rows are delivered by {@code outbox.OutboxDispatcher}.
 */
public interface Outbox {

    void append(UUID aggregateId, String eventType, String payloadJson, Instant now);
}

package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.UUID;

/** Port for the transactional outbox. Joins the caller's transaction; delivered by the relay (A4). */
public interface Outbox {

    void append(UUID aggregateId, String eventType, String payloadJson, Instant now);
}

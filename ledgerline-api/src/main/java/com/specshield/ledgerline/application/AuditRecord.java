package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.UUID;

/** One audit log row. {@code detailJson} is a JSON object. */
public record AuditRecord(
        Long id, UUID orderId, String kind, String detailJson, String actor, String correlationId, Instant createdAt) {}

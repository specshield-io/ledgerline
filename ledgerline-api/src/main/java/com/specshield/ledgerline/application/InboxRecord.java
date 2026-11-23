package com.specshield.ledgerline.application;

import java.time.Instant;

/** A provider event as stored in the inbox. {@code payloadJson} is the raw JSON payload. */
public record InboxRecord(
        Long id,
        String provider,
        String providerEventId,
        String payloadJson,
        boolean signatureValid,
        Instant receivedAt,
        Instant processedAt,
        String outcome) {}

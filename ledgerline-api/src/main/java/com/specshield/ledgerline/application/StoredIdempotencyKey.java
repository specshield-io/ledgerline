package com.specshield.ledgerline.application;

import java.time.Instant;

public record StoredIdempotencyKey(
        String key,
        String requestHash,
        boolean completed,
        StoredResponse response,
        Instant createdAt,
        Instant expiresAt) {}

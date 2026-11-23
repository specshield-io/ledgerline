package com.specshield.ledgerline.domain;

import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Generates RFC 9562 version 7 UUIDs: a 48-bit Unix millisecond timestamp followed by random bits. They sort by
 * creation time, which keeps primary-key inserts index friendly.
 */
public final class OrderIds {

    private final Clock clock;
    private final RandomGenerator random;

    public OrderIds(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    public UUID next() {
        long millis = clock.millis();
        if (millis < 0 || millis >= (1L << 48)) {
            throw new IllegalStateException("clock out of UUIDv7 range: " + millis);
        }
        long randA = random.nextLong() & 0x0FFFL; // 12 bits
        long msb = (millis << 16) | (0x7L << 12) | randA; // version 7
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 0b10
        return new UUID(msb, lsb);
    }
}

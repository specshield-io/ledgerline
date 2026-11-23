package com.specshield.ledgerline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can move. Thread safe. */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-01-15T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock is always UTC");
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    public void advance(Duration duration) {
        now.updateAndGet(t -> t.plus(duration));
    }

    public void reset() {
        now.set(START);
    }
}

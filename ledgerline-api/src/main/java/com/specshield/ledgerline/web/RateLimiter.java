package com.specshield.ledgerline.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * In-memory token buckets per (plan, client IP), refilled in full once a minute. Bucket time comes from the injected
 * {@link Clock}, so tests control the window. Buckets idle for 10 minutes are dropped, and at most 100,000 are kept,
 * so a flood of distinct addresses cannot exhaust memory. Per instance: with several instances the effective limit is
 * multiplied by their number.
 */
public class RateLimiter {

    public enum Plan {
        PUBLIC,
        CHAOS
    }

    /** {@code retryAfterSeconds} is 0 when allowed. */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {}

    private record Key(Plan plan, String client) {}

    private final Cache<Key, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();
    private final RateLimitProperties properties;
    private final TimeMeter time;

    public RateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.time = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                Instant now = clock.instant();
                return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    public Decision tryConsume(Plan plan, String client) {
        ConsumptionProbe probe =
                buckets.get(new Key(plan, client), k -> newBucket(plan)).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, probe.getRemainingTokens(), 0);
        }
        long seconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        return new Decision(false, 0, seconds);
    }

    /** Forgets every bucket (tests). */
    public void reset() {
        buckets.invalidateAll();
    }

    private Bucket newBucket(Plan plan) {
        int perMinute = plan == Plan.CHAOS ? properties.chaosPerMinute() : properties.publicPerMinute();
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perMinute)
                        .refillIntervally(perMinute, Duration.ofMinutes(1))
                        .build())
                .withCustomTimePrecision(time)
                .build();
    }
}

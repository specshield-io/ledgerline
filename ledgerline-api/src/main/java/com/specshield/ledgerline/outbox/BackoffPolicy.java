package com.specshield.ledgerline.outbox;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter: the delay before attempt {@code n} is uniformly random in {@code [0, min(cap,
 * base * 2^n)]}. Full jitter spreads retries from many failed rows over the whole window, so a recovering merchant
 * is not hit by a synchronised wave. After {@code maxAttempts} attempts the answer is {@link Dead}.
 *
 * <p>Pure: no clock, no I/O. The random source is injected, so a seeded generator makes it deterministic.
 */
public final class BackoffPolicy {

    /** What to do before attempt {@code n}. */
    public sealed interface Decision permits Retry, Dead {}

    public record Retry(Duration delay) implements Decision {}

    public record Dead() implements Decision {}

    private final long baseMillis;
    private final long capMillis;
    private final int maxAttempts;
    private final RandomGenerator random;

    public BackoffPolicy(Duration base, Duration cap, int maxAttempts, RandomGenerator random) {
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("cap must not be below base");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.baseMillis = base.toMillis();
        this.capMillis = cap.toMillis();
        this.maxAttempts = maxAttempts;
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * @param attempt the number of the attempt about to be scheduled (the first retry is attempt 2)
     * @return a random delay within {@link #upperBound(int)}, or {@link Dead} when {@code attempt > maxAttempts}
     */
    public Decision next(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1, was " + attempt);
        }
        if (attempt > maxAttempts) {
            return new Dead();
        }
        long bound = upperBound(attempt).toMillis();
        return new Retry(Duration.ofMillis(random.nextLong(bound + 1)));
    }

    /** {@code min(cap, base * 2^attempt)}, without overflowing for large attempts. */
    public Duration upperBound(int attempt) {
        if (attempt >= Long.SIZE - 1 || baseMillis > (capMillis >> attempt)) {
            return Duration.ofMillis(capMillis);
        }
        return Duration.ofMillis(Math.min(capMillis, baseMillis << attempt));
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}

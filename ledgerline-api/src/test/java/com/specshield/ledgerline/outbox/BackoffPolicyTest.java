package com.specshield.ledgerline.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackoffPolicyTest {

    private static final Duration BASE = Duration.ofSeconds(2);
    private static final Duration CAP = Duration.ofHours(1);

    private static BackoffPolicy policy(RandomGenerator random) {
        return new BackoffPolicy(BASE, CAP, 8, random);
    }

    @ParameterizedTest(name = "attempt {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("LL-U14 the delay for attempt n is within [0, min(cap, base * 2^n)]")
    void delayWithinBounds(int attempt) {
        long bound = Math.min(CAP.toMillis(), BASE.toMillis() << attempt);
        assertThat(policy(new SplittableRandom()).upperBound(attempt)).isEqualTo(Duration.ofMillis(bound));

        BackoffPolicy policy = policy(new SplittableRandom(attempt));
        for (int i = 0; i < 2_000; i++) {
            BackoffPolicy.Decision decision = policy.next(attempt);
            assertThat(decision).isInstanceOf(BackoffPolicy.Retry.class);
            Duration delay = ((BackoffPolicy.Retry) decision).delay();
            assertThat(delay).isBetween(Duration.ZERO, Duration.ofMillis(bound));
        }
    }

    @Test
    @DisplayName("LL-U14 the jitter reaches both ends of the window: 0 and the upper bound are possible")
    void boundsAreInclusive() {
        BackoffPolicy low = policy(fixed(0));
        BackoffPolicy high = policy(new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public long nextLong(long bound) {
                return bound - 1; // the largest value the generator may return
            }
        });

        assertThat(low.next(3)).isEqualTo(new BackoffPolicy.Retry(Duration.ZERO));
        assertThat(high.next(3)).isEqualTo(new BackoffPolicy.Retry(Duration.ofSeconds(16)));
    }

    @Test
    @DisplayName("LL-U14 deterministic with a seeded RandomGenerator: the same seed gives the same delays")
    void deterministicWithSeed() {
        assertThat(delays(policy(new SplittableRandom(42)))).isEqualTo(delays(policy(new SplittableRandom(42))));
        assertThat(delays(policy(new SplittableRandom(42)))).isNotEqualTo(delays(policy(new SplittableRandom(43))));
    }

    @Test
    @DisplayName("LL-U14 attempt 9 (after 8 attempts) returns DEAD")
    void deadAfterMaxAttempts() {
        BackoffPolicy policy = policy(new SplittableRandom(1));

        assertThat(policy.next(8)).isInstanceOf(BackoffPolicy.Retry.class);
        assertThat(policy.next(9)).isEqualTo(new BackoffPolicy.Dead());
        assertThat(policy.next(100)).isEqualTo(new BackoffPolicy.Dead());
        assertThat(policy.maxAttempts()).isEqualTo(8);
    }

    @Test
    @DisplayName("LL-U14 the cap bounds the delay, without overflow for large attempt numbers")
    void capApplies() {
        BackoffPolicy policy = new BackoffPolicy(BASE, CAP, 1_000, fixed(0));

        assertThat(policy.upperBound(10)).isEqualTo(Duration.ofMillis(2_048_000));
        assertThat(policy.upperBound(11)).isEqualTo(CAP); // 4_096 s > 1 h
        assertThat(policy.upperBound(62)).isEqualTo(CAP);
        assertThat(policy.upperBound(63)).isEqualTo(CAP);
        assertThat(policy.upperBound(500)).isEqualTo(CAP);
        assertThat(policy.next(500)).isEqualTo(new BackoffPolicy.Retry(Duration.ZERO));
    }

    @Test
    @DisplayName("invalid settings and attempt numbers are rejected")
    void rejectsInvalidInput() {
        RandomGenerator random = new SplittableRandom(1);
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(Duration.ZERO, CAP, 8, random));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(-1), CAP, 8, random));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BackoffPolicy(BASE, Duration.ofSeconds(1), 8, random));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(BASE, CAP, 0, random));
        assertThatIllegalArgumentException().isThrownBy(() -> policy(random).next(0));
    }

    private static List<Duration> delays(BackoffPolicy policy) {
        List<Duration> delays = new ArrayList<>();
        for (int attempt = 1; attempt <= 8; attempt++) {
            delays.add(((BackoffPolicy.Retry) policy.next(attempt)).delay());
        }
        return delays;
    }

    private static RandomGenerator fixed(long value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public long nextLong(long bound) {
                return value;
            }
        };
    }
}

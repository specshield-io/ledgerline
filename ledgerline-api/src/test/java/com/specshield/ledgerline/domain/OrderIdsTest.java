package com.specshield.ledgerline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderIdsTest {

    @Test
    @DisplayName("generates RFC 9562 version 7 UUIDs carrying the clock's milliseconds")
    void version7() {
        Instant now = Instant.parse("2026-01-15T10:00:00.123Z");
        OrderIds ids = new OrderIds(Clock.fixed(now, ZoneOffset.UTC), new Random(42));

        UUID id = ids.next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
        assertThat(ids.next()).isNotEqualTo(id);
    }

    @Test
    @DisplayName("ids from a later millisecond sort after earlier ones")
    void timeOrdered() {
        Random random = new Random(7);
        UUID earlier = new OrderIds(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC), random).next();
        UUID later = new OrderIds(Clock.fixed(Instant.ofEpochMilli(1_001), ZoneOffset.UTC), random).next();

        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    @DisplayName("the 48-bit timestamp range is enforced at both ends")
    void timestampBounds() {
        long max = (1L << 48) - 1;
        assertThat(idAt(0).getMostSignificantBits() >>> 16).isZero();
        assertThat(idAt(max).getMostSignificantBits() >>> 16).isEqualTo(max);
        assertThatIllegalStateException().isThrownBy(() -> idAt(max + 1));
    }

    @Test
    @DisplayName("only the version and variant bits are fixed; the rest comes from the random source")
    void bitLayout() {
        RandomGenerator zeros = () -> 0L;
        RandomGenerator ones = () -> -1L;
        Clock epoch = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

        UUID min = new OrderIds(epoch, zeros).next();
        UUID max = new OrderIds(epoch, ones).next();

        assertThat(min).isEqualTo(new UUID(0x0000_0000_0000_7000L, 0x8000_0000_0000_0000L));
        assertThat(max).isEqualTo(new UUID(0x0000_0000_0000_7FFFL, 0xBFFF_FFFF_FFFF_FFFFL));
    }

    private static UUID idAt(long epochMilli) {
        return new OrderIds(Clock.fixed(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC), new Random(3)).next();
    }

    @Test
    @DisplayName("a clock before 1970 is rejected")
    void outOfRange() {
        OrderIds ids = new OrderIds(Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC), new Random(1));

        assertThatIllegalStateException().isThrownBy(ids::next);
    }
}

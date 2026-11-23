package com.specshield.ledgerline.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Synthetic test data only. */
final class Fixtures {

    static final Instant T0 = Instant.parse("2026-01-15T10:00:00.123456Z");
    static final Clock CLOCK = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    static final UUID ORDER_ID = UUID.fromString("0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
    static final long AMOUNT = 10_000; // USD 100.00

    private Fixtures() {}

    static Order newOrder() {
        return Order.create(ORDER_ID, "synthetic-merchant-ref-001", AMOUNT, Money.USD, T0);
    }

    /** An order already in {@code status}, with {@code lastEventSequence} events applied. */
    static Order orderIn(OrderStatus status, long refundedMinor, long lastEventSequence) {
        return new Order(
                ORDER_ID,
                "synthetic-merchant-ref-001",
                AMOUNT,
                Money.USD,
                status,
                refundedMinor,
                lastEventSequence,
                0,
                T0,
                T0,
                lastEventSequence == 0 ? null : T0);
    }

    static PaymentEvent event(PaymentEventType type, long sequence) {
        return event(type, sequence, AMOUNT);
    }

    static PaymentEvent event(PaymentEventType type, long sequence, long amountMinor) {
        return new PaymentEvent("evt-" + sequence, type, ORDER_ID, amountMinor, T0.plusSeconds(sequence), sequence);
    }
}

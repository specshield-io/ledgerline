package com.specshield.ledgerline.chaos;

import java.util.Arrays;
import java.util.Optional;

/** The scenarios a visitor can start, by URL name. */
public enum Scenario {
    HAPPY_PATH("happy-path", "Create an order and pay it through MockPay; ends PAID."),
    DUPLICATE_STORM("duplicate-storm", "The success event is delivered 5 times at once; applied once, ends PAID."),
    TAMPERED_WEBHOOK(
            "tampered-webhook", "The success event's body is altered after signing; rejected, stays PAYMENT_PENDING."),
    REPLAY_ATTACK(
            "replay-attack",
            "The success event carries a valid signature 10 minutes old; rejected, stays PAYMENT_PENDING."),
    OUT_OF_ORDER("out-of-order", "SUCCEEDED (sequence 2) arrives, then a late FAILED (sequence 1); STALE, ends PAID."),
    MERCHANT_DOWN(
            "merchant-down",
            "The demo merchant answers 500 for 2 minutes; the order.paid notification is retried until delivered."),
    MERCHANT_SLOW(
            "merchant-slow",
            "The demo merchant takes 10 s for 2 minutes; deliveries time out after 3 s and are retried."),
    REFUND_FLOW("refund-flow", "Pay, refund part, then refund the rest; ends REFUNDED.");

    private final String slug;
    private final String description;

    Scenario(String slug, String description) {
        this.slug = slug;
        this.description = description;
    }

    public String slug() {
        return slug;
    }

    public String description() {
        return description;
    }

    public static Optional<Scenario> fromSlug(String slug) {
        return Arrays.stream(values()).filter(s -> s.slug.equals(slug)).findFirst();
    }

    /** Needs the built-in demo merchant (local profile). */
    boolean needsMerchantSink() {
        return this == MERCHANT_DOWN || this == MERCHANT_SLOW;
    }
}

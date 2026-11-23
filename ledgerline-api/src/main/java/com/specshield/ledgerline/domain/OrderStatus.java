package com.specshield.ledgerline.domain;

public enum OrderStatus {
    CREATED,
    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    CANCELLED;

    /** True once a payment has succeeded: PAID or any refund state. */
    public boolean isPaidOrLater() {
        return this == PAID || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}

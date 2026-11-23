package com.specshield.ledgerline.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * An order and its payment state. Immutable: every change produces a new instance.
 *
 * <p>Invariants, checked on construction:
 *
 * <ul>
 *   <li>{@code merchantRef} is not blank and at most {@value #MERCHANT_REF_MAX_LENGTH} characters;
 *   <li>{@code amountMinor} is between {@value #MIN_AMOUNT_MINOR} and {@value #MAX_AMOUNT_MINOR};
 *   <li>{@code currency} is {@code "USD"};
 *   <li>{@code refundedMinor} matches the status: 0 before any refund, strictly between 0 and the amount when
 *       {@code PARTIALLY_REFUNDED}, equal to the amount when {@code REFUNDED};
 *   <li>{@code lastEventSequence} is 0 (no provider event applied yet) or the sequence of the last applied event.
 * </ul>
 *
 * <p>All instants are UTC and truncated to microseconds, the precision PostgreSQL {@code timestamptz} stores, so
 * an order read back from the database equals the one that was saved.
 */
public record Order(
        UUID id,
        String merchantRef,
        long amountMinor,
        String currency,
        OrderStatus status,
        long refundedMinor,
        long lastEventSequence,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant lastProviderEventAt) {

    public static final int MERCHANT_REF_MAX_LENGTH = 64;
    public static final long MIN_AMOUNT_MINOR = 1;
    public static final long MAX_AMOUNT_MINOR = 100_000_000;

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        requireValidMerchantRef(merchantRef);
        requireValidAmount(amountMinor);
        Money.requireSupportedCurrency(currency);
        requireRefundedConsistentWithStatus(status, amountMinor, refundedMinor);
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("lastEventSequence must not be negative, was " + lastEventSequence);
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative, was " + version);
        }
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
        lastProviderEventAt = lastProviderEventAt == null ? null : lastProviderEventAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** A new order in {@link OrderStatus#CREATED}. */
    public static Order create(UUID id, String merchantRef, long amountMinor, String currency, Instant now) {
        return new Order(id, merchantRef, amountMinor, currency, OrderStatus.CREATED, 0, 0, 0, now, now, null);
    }

    public Money amount() {
        return new Money(amountMinor, currency);
    }

    public Money refunded() {
        return new Money(refundedMinor, currency);
    }

    /** Amount that can still be refunded. */
    public Money refundable() {
        return amount().minus(refunded());
    }

    Order withPaymentState(
            OrderStatus newStatus, long newRefundedMinor, long sequence, Instant providerEventAt, Instant now) {
        return new Order(
                id,
                merchantRef,
                amountMinor,
                currency,
                newStatus,
                newRefundedMinor,
                sequence,
                version,
                createdAt,
                now,
                providerEventAt);
    }

    Order withStatus(OrderStatus newStatus, Instant now) {
        return new Order(
                id,
                merchantRef,
                amountMinor,
                currency,
                newStatus,
                refundedMinor,
                lastEventSequence,
                version,
                createdAt,
                now,
                lastProviderEventAt);
    }

    static void requireValidAmount(long amountMinor) {
        if (amountMinor < MIN_AMOUNT_MINOR || amountMinor > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("amountMinor must be between " + MIN_AMOUNT_MINOR + " and "
                    + MAX_AMOUNT_MINOR + ", was " + amountMinor);
        }
    }

    private static void requireValidMerchantRef(String merchantRef) {
        if (merchantRef == null || merchantRef.isBlank()) {
            throw new IllegalArgumentException("merchantRef must not be blank");
        }
        if (merchantRef.length() > MERCHANT_REF_MAX_LENGTH) {
            throw new IllegalArgumentException("merchantRef must be at most " + MERCHANT_REF_MAX_LENGTH
                    + " characters, was " + merchantRef.length());
        }
    }

    private static void requireRefundedConsistentWithStatus(OrderStatus status, long amountMinor, long refunded) {
        boolean consistent = switch (status) {
            case PARTIALLY_REFUNDED -> refunded > 0 && refunded < amountMinor;
            case REFUNDED -> refunded == amountMinor;
            default -> refunded == 0;
        };
        if (!consistent) {
            throw new IllegalArgumentException("refundedMinor " + refunded + " is inconsistent with status " + status
                    + " and amountMinor " + amountMinor);
        }
    }
}

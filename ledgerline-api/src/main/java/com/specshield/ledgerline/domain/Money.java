package com.specshield.ledgerline.domain;

/**
 * An amount of money in minor units (cents) together with its ISO 4217 currency.
 *
 * <p>Ledgerline supports {@code "USD"} only. Amounts are never negative. Arithmetic is overflow safe: it throws
 * {@link ArithmeticException} instead of wrapping around.
 */
public record Money(long amountMinor, String currency) implements Comparable<Money> {

    public static final String USD = "USD";

    public Money {
        requireSupportedCurrency(currency);
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must not be negative, was " + amountMinor);
        }
    }

    public static Money usd(long amountMinor) {
        return new Money(amountMinor, USD);
    }

    public static Money zero() {
        return usd(0);
    }

    /** Throws {@link IllegalArgumentException} unless {@code currency} is exactly {@code "USD"}. */
    public static void requireSupportedCurrency(String currency) {
        if (!USD.equals(currency)) {
            throw new IllegalArgumentException("currency must be " + USD + ", was " + currency);
        }
    }

    /** @throws ArithmeticException if the sum overflows {@code long} */
    public Money plus(Money other) {
        return new Money(Math.addExact(amountMinor, sameCurrency(other).amountMinor), currency);
    }

    /**
     * @throws ArithmeticException if the difference overflows {@code long}
     * @throws IllegalArgumentException if the result would be negative
     */
    public Money minus(Money other) {
        return new Money(Math.subtractExact(amountMinor, sameCurrency(other).amountMinor), currency);
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(amountMinor, sameCurrency(other).amountMinor);
    }

    private Money sameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
        return other;
    }
}

package com.specshield.ledgerline.domain;

import static com.specshield.ledgerline.domain.Fixtures.ORDER_ID;
import static com.specshield.ledgerline.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderValidationTest {

    @ParameterizedTest(name = "amountMinor={0}")
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, 100_000_001, Long.MAX_VALUE})
    @DisplayName("LL-U07 amount outside 1..100_000_000 is rejected (0, negative, above max)")
    void invalidAmounts(long amountMinor) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Order.create(ORDER_ID, "ref", amountMinor, Money.USD, T0))
                .withMessageContaining("amountMinor must be between 1 and 100000000");
    }

    @ParameterizedTest(name = "amountMinor={0}")
    @ValueSource(longs = {1, 100_000_000})
    @DisplayName("LL-U07 amount bounds 1 and 100_000_000 are accepted")
    void boundaryAmounts(long amountMinor) {
        assertThat(Order.create(ORDER_ID, "ref", amountMinor, Money.USD, T0).amount())
                .isEqualTo(Money.usd(amountMinor));
    }

    @ParameterizedTest(name = "currency={0}")
    @ValueSource(strings = {"EUR", "usd", "US", ""})
    @DisplayName("LL-U07 currency other than USD is rejected (EUR)")
    void invalidCurrency(String currency) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Order.create(ORDER_ID, "ref", 100, currency, T0))
                .withMessageContaining("currency must be USD");
    }

    @Test
    @DisplayName("LL-U07 null currency is rejected")
    void nullCurrency() {
        assertThatIllegalArgumentException().isThrownBy(() -> Order.create(ORDER_ID, "ref", 100, null, T0));
    }

    @Test
    @DisplayName("LL-U07 payment event amount follows the same bounds")
    void eventAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentEvent("evt-1", PaymentEventType.PAYMENT_INITIATED, ORDER_ID, 0, T0, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new PaymentEvent("evt-1", PaymentEventType.PAYMENT_INITIATED, ORDER_ID, 100_000_001, T0, 1));
    }

    @Test
    @DisplayName("merchantRef must be non-blank and at most 64 characters")
    void merchantRef() {
        assertThat(Order.create(ORDER_ID, "x".repeat(64), 100, Money.USD, T0).merchantRef())
                .hasSize(64);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Order.create(ORDER_ID, "x".repeat(65), 100, Money.USD, T0))
                .withMessageContaining("at most 64");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Order.create(ORDER_ID, "  ", 100, Money.USD, T0))
                .withMessageContaining("must not be blank");
        assertThatIllegalArgumentException().isThrownBy(() -> Order.create(ORDER_ID, null, 100, Money.USD, T0));
    }

    @Test
    @DisplayName("refundedMinor must be consistent with status")
    void refundedConsistency() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Fixtures.orderIn(OrderStatus.PAID, 1, 0))
                .withMessageContaining("inconsistent with status PAID");
        assertThatIllegalArgumentException().isThrownBy(() -> Fixtures.orderIn(OrderStatus.PARTIALLY_REFUNDED, 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Fixtures.orderIn(OrderStatus.PARTIALLY_REFUNDED, Fixtures.AMOUNT, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Fixtures.orderIn(OrderStatus.REFUNDED, Fixtures.AMOUNT - 1, 0));
    }

    @Test
    @DisplayName("sequence, version and required fields are validated")
    void otherFields() {
        assertThatIllegalArgumentException().isThrownBy(() -> Fixtures.orderIn(OrderStatus.PAID, 0, -1));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new Order(ORDER_ID, "ref", 100, Money.USD, OrderStatus.CREATED, 0, 0, -1, T0, T0, null))
                .withMessageContaining("version");
        assertThatNullPointerException()
                .isThrownBy(() -> new Order(null, "ref", 100, Money.USD, OrderStatus.CREATED, 0, 0, 0, T0, T0, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentEvent("evt-0", PaymentEventType.PAYMENT_INITIATED, ORDER_ID, 1, T0, 0))
                .withMessageContaining("sequence");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PaymentEvent(" ", PaymentEventType.PAYMENT_INITIATED, ORDER_ID, 1, T0, 1));
    }

    @Test
    @DisplayName("instants are truncated to microseconds, the precision PostgreSQL stores")
    void instantsTruncatedToMicros() {
        Instant withNanos = Instant.parse("2026-01-15T10:00:00.123456789Z");
        Order order = Order.create(ORDER_ID, "ref", 100, Money.USD, withNanos);

        assertThat(order.createdAt()).isEqualTo(Instant.parse("2026-01-15T10:00:00.123456Z"));
        assertThat(order.updatedAt()).isEqualTo(order.createdAt());
        assertThat(new PaymentEvent("e", PaymentEventType.PAYMENT_INITIATED, ORDER_ID, 1, withNanos, 1).occurredAt())
                .isEqualTo(Instant.parse("2026-01-15T10:00:00.123456Z"));
    }
}

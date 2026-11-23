package com.specshield.ledgerline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("LL-U07 Money rejects negative amounts and non-USD currencies")
    void validation() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.usd(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(1, "EUR"));
        assertThat(Money.zero().isZero()).isTrue();
        assertThat(Money.usd(1).isZero()).isFalse();
    }

    @Test
    @DisplayName("plus and minus are exact")
    void arithmetic() {
        assertThat(Money.usd(150).plus(Money.usd(50))).isEqualTo(Money.usd(200));
        assertThat(Money.usd(150).minus(Money.usd(50))).isEqualTo(Money.usd(100));
        assertThat(Money.usd(2).isGreaterThan(Money.usd(1))).isTrue();
        assertThat(Money.usd(1).isGreaterThan(Money.usd(1))).isFalse();
        assertThat(Money.usd(1).compareTo(Money.usd(2))).isNegative();
    }

    @Test
    @DisplayName("overflow throws instead of wrapping around")
    void overflow() {
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> Money.usd(Long.MAX_VALUE).plus(Money.usd(1)));
    }

    @Test
    @DisplayName("a negative result is rejected")
    void negativeResult() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.usd(1).minus(Money.usd(2)));
    }
}

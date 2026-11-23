package com.specshield.ledgerline.domain;

import static com.specshield.ledgerline.domain.Fixtures.AMOUNT;
import static com.specshield.ledgerline.domain.Fixtures.CLOCK;
import static com.specshield.ledgerline.domain.Fixtures.T0;
import static com.specshield.ledgerline.domain.Fixtures.event;
import static com.specshield.ledgerline.domain.Fixtures.newOrder;
import static com.specshield.ledgerline.domain.Fixtures.orderIn;
import static com.specshield.ledgerline.domain.PaymentEventType.PAYMENT_FAILED;
import static com.specshield.ledgerline.domain.PaymentEventType.PAYMENT_INITIATED;
import static com.specshield.ledgerline.domain.PaymentEventType.PAYMENT_SUCCEEDED;
import static com.specshield.ledgerline.domain.PaymentEventType.REFUND_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderStateMachineTest {

    private final OrderStateMachine machine = new OrderStateMachine(CLOCK);

    @Test
    @DisplayName("LL-U01 CREATED to PAYMENT_PENDING on PAYMENT_INITIATED")
    void createdToPending() {
        Order order = applied(newOrder(), event(PAYMENT_INITIATED, 1));

        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.lastEventSequence()).isEqualTo(1);
        assertThat(order.lastProviderEventAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(order.updatedAt()).isEqualTo(CLOCK.instant());
        assertThat(order.createdAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("LL-U02 PAYMENT_PENDING to PAID on PAYMENT_SUCCEEDED")
    void pendingToPaid() {
        Order order = applied(orderIn(OrderStatus.PAYMENT_PENDING, 0, 1), event(PAYMENT_SUCCEEDED, 2));

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.lastEventSequence()).isEqualTo(2);
        assertThat(order.refundedMinor()).isZero();
    }

    @Test
    @DisplayName("LL-U03 PAYMENT_PENDING to PAYMENT_FAILED on PAYMENT_FAILED; then PAYMENT_SUCCEEDED moves to PAID")
    void failedThenRetrySucceeds() {
        Order failed = applied(orderIn(OrderStatus.PAYMENT_PENDING, 0, 1), event(PAYMENT_FAILED, 2));
        assertThat(failed.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);

        Order paid = applied(failed, event(PAYMENT_SUCCEEDED, 3));
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.lastEventSequence()).isEqualTo(3);
    }

    @Test
    @DisplayName("LL-U04 partial refund then full refund reaches REFUNDED; refund above remaining throws")
    void partialThenFullRefund() {
        Order paid = orderIn(OrderStatus.PAID, 0, 2);

        Order partial = applied(paid, event(REFUND_SUCCEEDED, 3, 3_000));
        assertThat(partial.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(partial.refundedMinor()).isEqualTo(3_000);
        assertThat(partial.refundable()).isEqualTo(Money.usd(7_000));

        Order stillPartial = applied(partial, event(REFUND_SUCCEEDED, 4, 2_000));
        assertThat(stillPartial.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(stillPartial.refundedMinor()).isEqualTo(5_000);

        assertThatThrownBy(() -> machine.apply(stillPartial, event(REFUND_SUCCEEDED, 5, 5_001)))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("from=PARTIALLY_REFUNDED")
                .hasMessageContaining("to=REFUNDED")
                .hasMessageContaining("event=REFUND_SUCCEEDED")
                .hasMessageContaining("exceeds refundable amount 5000");

        Order refunded = applied(stillPartial, event(REFUND_SUCCEEDED, 5, 5_000));
        assertThat(refunded.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.refundedMinor()).isEqualTo(AMOUNT);
        assertThat(refunded.refundable().isZero()).isTrue();
    }

    @Test
    @DisplayName("LL-U04 refund of the full amount from PAID goes straight to REFUNDED")
    void fullRefundFromPaid() {
        Order refunded = applied(orderIn(OrderStatus.PAID, 0, 2), event(REFUND_SUCCEEDED, 3, AMOUNT));

        assertThat(refunded.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.refundedMinor()).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("LL-U06 PAYMENT_FAILED after PAID is STALE; lower sequence is STALE; state unchanged")
    void staleEventsDoNotChangeState() {
        Order paid = orderIn(OrderStatus.PAID, 0, 5);

        Transition lateFailure = machine.apply(paid, event(PAYMENT_FAILED, 6));
        assertThat(lateFailure).isInstanceOf(Transition.Stale.class);
        assertThat(((Transition.Stale) lateFailure).reason()).contains("after the payment succeeded");

        Transition lowerSequence = machine.apply(paid, event(REFUND_SUCCEEDED, 4, 100));
        assertThat(lowerSequence).isInstanceOf(Transition.Stale.class);
        assertThat(((Transition.Stale) lowerSequence).reason()).contains("sequence <= last applied sequence 5");

        Transition equalSequence = machine.apply(paid, event(REFUND_SUCCEEDED, 5, 100));
        assertThat(equalSequence).isInstanceOf(Transition.Stale.class);

        // The input is immutable, so "state unchanged" means the order is still exactly what it was.
        assertThat(paid).isEqualTo(orderIn(OrderStatus.PAID, 0, 5));
    }

    @Test
    @DisplayName("LL-U06 lower sequence is STALE even when the event would otherwise be illegal")
    void staleWinsOverIllegal() {
        Transition t = machine.apply(orderIn(OrderStatus.PAID, 0, 5), event(PAYMENT_INITIATED, 1));

        assertThat(t).isInstanceOf(Transition.Stale.class);
    }

    @Test
    @DisplayName("cancel moves CREATED to CANCELLED")
    void cancelCreated() {
        Order cancelled = machine.cancel(newOrder());

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.updatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    @DisplayName("cancel from any state other than CREATED throws")
    void cancelOtherStatesThrows() {
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.CREATED) {
                continue;
            }
            long refunded = status == OrderStatus.PARTIALLY_REFUNDED ? 1 : status == OrderStatus.REFUNDED ? AMOUNT : 0;
            Order order = orderIn(status, refunded, 0);
            assertThatThrownBy(() -> machine.cancel(order))
                    .as(status.name())
                    .isInstanceOfSatisfying(IllegalTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(status);
                        assertThat(e.to()).isEqualTo(OrderStatus.CANCELLED);
                        assertThat(e.event()).isEqualTo("cancel()");
                    });
        }
    }

    @Test
    @DisplayName("events on a CANCELLED order are IGNORED, whatever their sequence")
    void cancelledIgnoresEvents() {
        Order cancelled = machine.cancel(newOrder());

        assertThat(machine.apply(cancelled, event(PAYMENT_INITIATED, 1))).isInstanceOf(Transition.Ignored.class);
        assertThat(((Transition.Ignored) machine.apply(cancelled, event(PAYMENT_SUCCEEDED, 9))).reason())
                .contains("CANCELLED");
    }

    @Test
    @DisplayName("an event for another order is rejected")
    void eventForAnotherOrder() {
        PaymentEvent foreign = new PaymentEvent(
                "evt-x", PAYMENT_INITIATED, UUID.fromString("00000000-0000-7000-8000-000000000001"), AMOUNT, T0, 1);

        assertThatThrownBy(() -> machine.apply(newOrder(), foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is for order");
    }

    @Test
    @DisplayName("Applied requires newStatus to match the order it carries")
    void appliedConsistency() {
        assertThatThrownBy(() -> new Transition.Applied(OrderStatus.PAID, newOrder()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Order applied(Order order, PaymentEvent event) {
        Transition t = machine.apply(order, event);
        assertThat(t).isInstanceOf(Transition.Applied.class);
        Transition.Applied applied = (Transition.Applied) t;
        assertThat(applied.newStatus()).isEqualTo(applied.order().status());
        return applied.order();
    }
}

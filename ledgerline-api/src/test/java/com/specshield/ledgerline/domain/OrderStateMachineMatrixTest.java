package com.specshield.ledgerline.domain;

import static com.specshield.ledgerline.domain.Fixtures.AMOUNT;
import static com.specshield.ledgerline.domain.Fixtures.CLOCK;
import static com.specshield.ledgerline.domain.Fixtures.event;
import static com.specshield.ledgerline.domain.Fixtures.orderIn;
import static com.specshield.ledgerline.domain.OrderStatus.CANCELLED;
import static com.specshield.ledgerline.domain.OrderStatus.CREATED;
import static com.specshield.ledgerline.domain.OrderStatus.PAID;
import static com.specshield.ledgerline.domain.OrderStatus.PARTIALLY_REFUNDED;
import static com.specshield.ledgerline.domain.OrderStatus.PAYMENT_FAILED;
import static com.specshield.ledgerline.domain.OrderStatus.PAYMENT_PENDING;
import static com.specshield.ledgerline.domain.OrderStatus.REFUNDED;
import static com.specshield.ledgerline.domain.PaymentEventType.PAYMENT_INITIATED;
import static com.specshield.ledgerline.domain.PaymentEventType.PAYMENT_SUCCEEDED;
import static com.specshield.ledgerline.domain.PaymentEventType.REFUND_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * LL-U05: every (status, eventType) pair. The table is written out by hand on purpose: it is the specification, not
 * a copy of the implementation. The event's sequence is always above the order's last sequence, so only the status
 * rules decide. Refund events are for 100 cents, below the refundable amount wherever a refund is allowed.
 */
class OrderStateMachineMatrixTest {

    private static final long LAST_SEQUENCE = 5;
    private static final long REFUND = 100;

    private final OrderStateMachine machine = new OrderStateMachine(CLOCK);

    sealed interface Expected {
        record Applied(OrderStatus to) implements Expected {}

        record Stale() implements Expected {}

        record Ignored() implements Expected {}

        record Throws() implements Expected {}
    }

    record Case(OrderStatus from, PaymentEventType event, Expected expected) {
        @Override
        public String toString() {
            return from + " + " + event + " -> " + expected;
        }
    }

    private static final Expected THROWS = new Expected.Throws();
    private static final Expected STALE = new Expected.Stale();
    private static final Expected IGNORED = new Expected.Ignored();

    private static Expected to(OrderStatus status) {
        return new Expected.Applied(status);
    }

    static final List<Case> MATRIX = List.of(
            new Case(CREATED, PAYMENT_INITIATED, to(PAYMENT_PENDING)),
            new Case(CREATED, PAYMENT_SUCCEEDED, THROWS),
            new Case(CREATED, PaymentEventType.PAYMENT_FAILED, THROWS),
            new Case(CREATED, REFUND_SUCCEEDED, THROWS),
            new Case(PAYMENT_PENDING, PAYMENT_INITIATED, THROWS),
            new Case(PAYMENT_PENDING, PAYMENT_SUCCEEDED, to(PAID)),
            new Case(PAYMENT_PENDING, PaymentEventType.PAYMENT_FAILED, to(PAYMENT_FAILED)),
            new Case(PAYMENT_PENDING, REFUND_SUCCEEDED, THROWS),
            new Case(PAYMENT_FAILED, PAYMENT_INITIATED, THROWS),
            new Case(PAYMENT_FAILED, PAYMENT_SUCCEEDED, to(PAID)),
            new Case(PAYMENT_FAILED, PaymentEventType.PAYMENT_FAILED, THROWS),
            new Case(PAYMENT_FAILED, REFUND_SUCCEEDED, THROWS),
            new Case(PAID, PAYMENT_INITIATED, THROWS),
            new Case(PAID, PAYMENT_SUCCEEDED, THROWS),
            new Case(PAID, PaymentEventType.PAYMENT_FAILED, STALE),
            new Case(PAID, REFUND_SUCCEEDED, to(PARTIALLY_REFUNDED)),
            new Case(PARTIALLY_REFUNDED, PAYMENT_INITIATED, THROWS),
            new Case(PARTIALLY_REFUNDED, PAYMENT_SUCCEEDED, THROWS),
            new Case(PARTIALLY_REFUNDED, PaymentEventType.PAYMENT_FAILED, STALE),
            new Case(PARTIALLY_REFUNDED, REFUND_SUCCEEDED, to(PARTIALLY_REFUNDED)),
            new Case(REFUNDED, PAYMENT_INITIATED, THROWS),
            new Case(REFUNDED, PAYMENT_SUCCEEDED, THROWS),
            new Case(REFUNDED, PaymentEventType.PAYMENT_FAILED, STALE),
            new Case(REFUNDED, REFUND_SUCCEEDED, THROWS), // nothing left to refund
            new Case(CANCELLED, PAYMENT_INITIATED, IGNORED),
            new Case(CANCELLED, PAYMENT_SUCCEEDED, IGNORED),
            new Case(CANCELLED, PaymentEventType.PAYMENT_FAILED, IGNORED),
            new Case(CANCELLED, REFUND_SUCCEEDED, IGNORED));

    static Stream<Case> matrix() {
        return MATRIX.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    @DisplayName("LL-U05 transition matrix over every (status, eventType) pair")
    void transitionMatrix(Case c) {
        Order before = orderFor(c.from());
        PaymentEvent event = event(c.event(), LAST_SEQUENCE + 1, c.event() == REFUND_SUCCEEDED ? REFUND : AMOUNT);

        switch (c.expected()) {
            case Expected.Applied(OrderStatus to) -> {
                Transition t = machine.apply(before, event);
                assertThat(t).isInstanceOfSatisfying(Transition.Applied.class, a -> {
                    assertThat(a.newStatus()).isEqualTo(to);
                    assertThat(a.order().lastEventSequence()).isEqualTo(LAST_SEQUENCE + 1);
                });
            }
            case Expected.Stale() -> assertThat(machine.apply(before, event)).isInstanceOf(Transition.Stale.class);
            case Expected.Ignored() -> assertThat(machine.apply(before, event)).isInstanceOf(Transition.Ignored.class);
            case Expected.Throws() ->
                assertThatThrownBy(() -> machine.apply(before, event))
                        .isInstanceOfSatisfying(IllegalTransitionException.class, e -> {
                            assertThat(e.from()).isEqualTo(c.from());
                            assertThat(e.getMessage())
                                    .contains("from=" + c.from())
                                    .contains("to=")
                                    .contains("event=" + c.event());
                        });
        }
    }

    @Test
    @DisplayName("LL-U05 the matrix covers every (status, eventType) pair exactly once")
    void matrixIsComplete() {
        Set<String> seen = new HashSet<>();
        for (Case c : MATRIX) {
            assertThat(seen.add(c.from() + "/" + c.event()))
                    .as("duplicate " + c)
                    .isTrue();
        }
        assertThat(seen).hasSize(EnumSet.allOf(OrderStatus.class).size() * PaymentEventType.values().length);
    }

    private static Order orderFor(OrderStatus status) {
        long refunded = switch (status) {
            case PARTIALLY_REFUNDED -> 4_000;
            case REFUNDED -> AMOUNT;
            default -> 0;
        };
        return orderIn(status, refunded, LAST_SEQUENCE);
    }
}

package com.specshield.ledgerline.domain;

import static com.specshield.ledgerline.domain.Fixtures.CLOCK;
import static com.specshield.ledgerline.domain.Fixtures.T0;
import static com.specshield.ledgerline.domain.Fixtures.event;
import static com.specshield.ledgerline.domain.Fixtures.newOrder;
import static com.specshield.ledgerline.domain.Fixtures.orderIn;
import static com.specshield.ledgerline.domain.PaymentEventType.PAYMENT_INITIATED;
import static com.specshield.ledgerline.domain.PaymentEventType.PAYMENT_SUCCEEDED;
import static com.specshield.ledgerline.domain.PaymentEventType.REFUND_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderEventSequencerTest {

    private final OrderStateMachine machine = new OrderStateMachine(CLOCK);
    private final OrderEventSequencer sequencer = new OrderEventSequencer(machine);

    @Test
    @DisplayName("applies out-of-order arrivals in sequence order")
    void appliesInSequenceOrder() {
        var result = sequencer.drain(newOrder(), List.of(event(PAYMENT_SUCCEEDED, 2), event(PAYMENT_INITIATED, 1)));

        assertThat(result.order().status()).isEqualTo(OrderStatus.PAID);
        assertThat(result.steps()).extracting(s -> s.event().sequence()).containsExactly(1L, 2L);
        assertThat(result.steps()).allMatch(OrderEventSequencer.Step::applied);
        assertThat(result.waiting()).isEmpty();
    }

    @Test
    @DisplayName("events after a gap wait until the missing event arrives")
    void gapWaits() {
        Order paid = orderIn(OrderStatus.PAID, 0, 2);
        PaymentEvent refund4 = event(REFUND_SUCCEEDED, 4, 1_000);
        PaymentEvent refund5 = event(REFUND_SUCCEEDED, 5, 2_000);

        var first = sequencer.drain(paid, List.of(refund5, refund4));
        assertThat(first.steps()).isEmpty();
        assertThat(first.waiting()).containsExactly(refund4, refund5);
        assertThat(first.order()).isEqualTo(paid);

        var second = sequencer.drain(paid, List.of(refund5, refund4, event(REFUND_SUCCEEDED, 3, 500)));
        assertThat(second.waiting()).isEmpty();
        assertThat(second.order().refundedMinor()).isEqualTo(3_500);
        assertThat(second.order().lastEventSequence()).isEqualTo(5);
    }

    @Test
    @DisplayName("a repeated providerEventId within a batch is reported as a duplicate and applied once")
    void duplicatesInBatch() {
        PaymentEvent initiated = event(PAYMENT_INITIATED, 1);

        var result = sequencer.drain(newOrder(), List.of(initiated, initiated));

        assertThat(result.duplicates()).containsExactly(initiated);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("an already applied sequence is STALE, including a second event id with the same sequence")
    void staleSequences() {
        PaymentEvent a = event(PAYMENT_INITIATED, 1);
        PaymentEvent sameSequenceOtherId =
                new PaymentEvent("evt-1-retry", PAYMENT_INITIATED, Fixtures.ORDER_ID, Fixtures.AMOUNT, T0, 1);

        var result = sequencer.drain(newOrder(), List.of(sameSequenceOtherId, a));

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).event()).isEqualTo(a); // sorted by sequence, then providerEventId
        assertThat(result.steps().get(0).applied()).isTrue();
        assertThat(result.steps().get(1).transition()).isInstanceOf(Transition.Stale.class);
    }

    @Test
    @DisplayName("an illegal in-sequence event is reported as failed and its successors wait behind it")
    void illegalEventBlocks() {
        PaymentEvent illegal = event(REFUND_SUCCEEDED, 1, 100); // refund before any payment
        PaymentEvent next = event(PAYMENT_SUCCEEDED, 2);

        var result = sequencer.drain(newOrder(), List.of(next, illegal));

        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.event()).isEqualTo(illegal);
            assertThat(step.applied()).isFalse();
            assertThat(step.failure()).isNotNull().hasMessageContaining("from=CREATED");
        });
        assertThat(result.waiting()).containsExactly(next);
        assertThat(result.order()).isEqualTo(newOrder());
    }

    @Test
    @DisplayName("on a CANCELLED order every event is IGNORED and nothing waits")
    void cancelledIgnoresEverything() {
        Order cancelled = machine.cancel(newOrder());

        var result = sequencer.drain(cancelled, List.of(event(PAYMENT_SUCCEEDED, 9), event(PAYMENT_INITIATED, 1)));

        assertThat(result.waiting()).isEmpty();
        assertThat(result.steps())
                .extracting(OrderEventSequencer.Step::transition)
                .allMatch(t -> t instanceof Transition.Ignored);
        assertThat(result.order()).isEqualTo(cancelled);
    }

    @Test
    @DisplayName("events for another order are rejected")
    void foreignEvent() {
        PaymentEvent foreign = new PaymentEvent(
                "evt-x", PAYMENT_INITIATED, UUID.fromString("00000000-0000-7000-8000-000000000001"), 1, T0, 1);

        assertThatIllegalArgumentException().isThrownBy(() -> sequencer.drain(newOrder(), List.of(foreign)));
    }

    @Test
    @DisplayName("a step carries exactly one of transition and failure")
    void stepInvariant() {
        PaymentEvent e = event(PAYMENT_INITIATED, 1);

        assertThatIllegalArgumentException().isThrownBy(() -> new OrderEventSequencer.Step(e, null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OrderEventSequencer.Step(
                        e,
                        new Transition.Ignored("x"),
                        new IllegalTransitionException(OrderStatus.CREATED, OrderStatus.PAID, "e", "r")));
    }
}

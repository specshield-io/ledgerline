package com.specshield.ledgerline.domain;

import static com.specshield.ledgerline.domain.Fixtures.CLOCK;
import static com.specshield.ledgerline.domain.Fixtures.ORDER_ID;
import static com.specshield.ledgerline.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * LL-P01. jqwik runs on its own JUnit Platform engine and reads {@code @Label}, not {@code @DisplayName}.
 *
 * <p>Delivery is simulated the way the inbox will work: each arriving event is stored unless its providerEventId is
 * already stored (the UNIQUE(provider, provider_event_id) constraint), then the production
 * {@link OrderEventSequencer} drains that order's unprocessed events. See {@link OrderEventSequencer} for why events
 * must be applied in sequence order rather than arrival order.
 */
class EventReplayPropertyTest {

    private static final int MAX_EVENTS = 30;

    private final OrderStateMachine machine = new OrderStateMachine(CLOCK);

    record Scenario(long amountMinor, List<PaymentEvent> canonical, List<PaymentEvent> delivered) {
        @Override
        public String toString() {
            return "amount=" + amountMinor + " canonical=" + canonical + " delivered=" + delivered;
        }
    }

    @Property(tries = 1000)
    @Label("LL-P01 duplicated and shuffled delivery converges to the canonical final status and refunded amount")
    void duplicatedAndShuffledDeliveryConverges(@ForAll("scenarios") Scenario scenario) {
        Order start = Order.create(ORDER_ID, "synthetic-merchant-ref-001", scenario.amountMinor(), Money.USD, T0);

        Order expected = start;
        for (PaymentEvent event : scenario.canonical()) {
            expected = ((Transition.Applied) machine.apply(expected, event)).order();
        }

        Inbox inbox = new Inbox(new OrderEventSequencer(machine), start);
        scenario.delivered().forEach(inbox::receive);
        Order actual = inbox.order();

        assertThat(inbox.unprocessed()).as("nothing left waiting").isEmpty();
        assertThat(inbox.appliedCount())
                .as("each canonical event applied exactly once")
                .isEqualTo(scenario.canonical().size());
        assertThat(inbox.nonAppliedSteps())
                .as("no STALE, IGNORED or failed steps")
                .isZero();
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.refundedMinor()).isEqualTo(expected.refundedMinor());
        assertThat(actual.lastEventSequence()).isEqualTo(scenario.canonical().size());
    }

    @Example
    @Label("naive in-arrival-order application loses an out-of-order refund (why the inbox must re-sequence)")
    void naiveApplicationLosesOutOfOrderRefund() {
        Order paid = Fixtures.orderIn(OrderStatus.PAID, 0, 3);
        PaymentEvent refund4 = Fixtures.event(PaymentEventType.REFUND_SUCCEEDED, 4, 1_000);
        PaymentEvent refund5 = Fixtures.event(PaymentEventType.REFUND_SUCCEEDED, 5, 2_000);

        Order afterFive = ((Transition.Applied) machine.apply(paid, refund5)).order();
        Transition lateFour = machine.apply(afterFive, refund4);

        assertThat(lateFour).isInstanceOf(Transition.Stale.class);
        assertThat(afterFive.refundedMinor()).isEqualTo(2_000); // canonical order would give 3_000

        Order resequenced = new OrderEventSequencer(machine)
                .drain(paid, List.of(refund5, refund4))
                .order();
        assertThat(resequenced.refundedMinor()).isEqualTo(3_000);
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Long> amounts = Arbitraries.longs().between(Order.MIN_AMOUNT_MINOR, Order.MAX_AMOUNT_MINOR);
        Arbitrary<Scenario> canonicalOnly = amounts.flatMap(amount -> Combinators.combine(
                        Arbitraries.of(true, false),
                        Arbitraries.longs().between(1, amount).list().ofMaxSize(MAX_EVENTS - 3),
                        Arbitraries.integers().between(1, MAX_EVENTS))
                .as((failFirst, refunds, length) ->
                        new Scenario(amount, canonicalHistory(amount, failFirst, refunds, length), List.of())));
        return canonicalOnly.flatMap(s -> Arbitraries.integers()
                .between(0, 4)
                .list()
                .ofSize(s.canonical().size())
                .flatMap(extraCopies -> Arbitraries.shuffle(withDuplicates(s.canonical(), extraCopies)))
                .map(delivered -> new Scenario(s.amountMinor(), s.canonical(), delivered)));
    }

    /**
     * A valid history: PAYMENT_INITIATED, optionally PAYMENT_FAILED, PAYMENT_SUCCEEDED, then refunds that never exceed
     * the amount, truncated to {@code length} events. Sequences run 1..n.
     */
    private static List<PaymentEvent> canonicalHistory(long amount, boolean failFirst, List<Long> refunds, int length) {
        List<PaymentEventType> types = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        types.add(PaymentEventType.PAYMENT_INITIATED);
        amounts.add(amount);
        if (failFirst) {
            types.add(PaymentEventType.PAYMENT_FAILED);
            amounts.add(amount);
        }
        types.add(PaymentEventType.PAYMENT_SUCCEEDED);
        amounts.add(amount);
        long remaining = amount;
        for (long refund : refunds) {
            if (remaining == 0) {
                break;
            }
            long r = Math.min(refund, remaining);
            types.add(PaymentEventType.REFUND_SUCCEEDED);
            amounts.add(r);
            remaining -= r;
        }
        int n = Math.min(length, types.size());
        List<PaymentEvent> history = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long seq = i + 1L;
            history.add(
                    new PaymentEvent("evt-" + seq, types.get(i), ORDER_ID, amounts.get(i), T0.plusSeconds(seq), seq));
        }
        return history;
    }

    private static List<PaymentEvent> withDuplicates(List<PaymentEvent> canonical, List<Integer> extraCopies) {
        List<PaymentEvent> all = new ArrayList<>();
        for (int i = 0; i < canonical.size(); i++) {
            for (int copy = 0; copy <= extraCopies.get(i); copy++) {
                all.add(canonical.get(i));
            }
        }
        return all;
    }

    /** In-memory stand-in for order_events_inbox for one order: unique by providerEventId, drained in sequence. */
    static final class Inbox {
        private final OrderEventSequencer sequencer;
        private final Map<String, PaymentEvent> stored = new LinkedHashMap<>();
        private final Set<String> processed = new HashSet<>();
        private Order order;
        private int applied;
        private int nonApplied;

        Inbox(OrderEventSequencer sequencer, Order start) {
            this.sequencer = sequencer;
            this.order = start;
        }

        void receive(PaymentEvent event) {
            if (stored.putIfAbsent(event.providerEventId(), event) != null) {
                return; // DUPLICATE: rejected by the unique constraint
            }
            OrderEventSequencer.Result result = sequencer.drain(order, unprocessed());
            order = result.order();
            for (OrderEventSequencer.Step step : result.steps()) {
                processed.add(step.event().providerEventId());
                if (step.applied()) {
                    applied++;
                } else {
                    nonApplied++;
                }
            }
        }

        List<PaymentEvent> unprocessed() {
            return stored.values().stream()
                    .filter(e -> !processed.contains(e.providerEventId()))
                    .toList();
        }

        Order order() {
            return order;
        }

        int appliedCount() {
            return applied;
        }

        int nonAppliedSteps() {
            return nonApplied;
        }
    }
}

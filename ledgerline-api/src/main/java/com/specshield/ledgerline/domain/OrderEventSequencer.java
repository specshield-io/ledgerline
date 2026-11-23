package com.specshield.ledgerline.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies one order's not-yet-processed provider events in sequence order.
 *
 * <p>Why this exists: {@link OrderStateMachine} treats an event whose sequence is at or below the last applied one as
 * STALE. Applying events in arrival order would therefore lose data. If refund #5 is applied before refund #4, #4 is
 * STALE and its amount is never recorded. The inbox must instead hand all pending events for an order to
 * {@link #drain}, which:
 *
 * <ol>
 *   <li>drops events whose providerEventId has already been seen in this batch (the inbox's unique constraint drops
 *       repeats across deliveries);
 *   <li>sorts by sequence (then providerEventId, for determinism);
 *   <li>applies every event up to and including the next expected sequence ({@code lastEventSequence + 1}),
 *       advancing as it goes;
 *   <li>leaves events after a gap {@linkplain Result#waiting() waiting}, to be retried when the missing event arrives.
 * </ol>
 *
 * <p>Assumption: the provider's per-order sequence has no gaps (1, 2, 3, ...). An event that waits indefinitely
 * means a missing predecessor; the inbox must surface it (alert, reconciliation), not apply it out of order.
 *
 * <p>An event the state machine rejects ({@link IllegalTransitionException}) is reported as a failed step and does not
 * advance the sequence, so its successors wait behind it. On a CANCELLED order every event is applied immediately,
 * which makes it IGNORED; nothing waits on a cancelled order.
 */
public final class OrderEventSequencer {

    private static final Comparator<PaymentEvent> BY_SEQUENCE =
            Comparator.comparingLong(PaymentEvent::sequence).thenComparing(PaymentEvent::providerEventId);

    private final OrderStateMachine machine;

    public OrderEventSequencer(OrderStateMachine machine) {
        this.machine = machine;
    }

    /** What happened to one event: a transition, or the reason it was rejected. Exactly one is non-null. */
    public record Step(PaymentEvent event, Transition transition, IllegalTransitionException failure) {
        public Step {
            Objects.requireNonNull(event, "event");
            if ((transition == null) == (failure == null)) {
                throw new IllegalArgumentException("exactly one of transition and failure must be set");
            }
        }

        public boolean applied() {
            return transition instanceof Transition.Applied;
        }
    }

    /**
     * @param order the order after all applied steps
     * @param steps processed events in the order they were handled
     * @param duplicates events dropped because their providerEventId already appeared in this batch
     * @param waiting events held back behind a gap, in sequence order
     */
    public record Result(Order order, List<Step> steps, List<PaymentEvent> duplicates, List<PaymentEvent> waiting) {
        public Result {
            steps = List.copyOf(steps);
            duplicates = List.copyOf(duplicates);
            waiting = List.copyOf(waiting);
        }
    }

    public Result drain(Order order, Collection<PaymentEvent> pending) {
        Set<String> seen = new HashSet<>();
        List<PaymentEvent> unique = new ArrayList<>();
        List<PaymentEvent> duplicates = new ArrayList<>();
        for (PaymentEvent event : pending) {
            if (!order.id().equals(event.orderId())) {
                throw new IllegalArgumentException("event " + event.providerEventId() + " is for order "
                        + event.orderId() + ", not " + order.id());
            }
            if (seen.add(event.providerEventId())) {
                unique.add(event);
            } else {
                duplicates.add(event);
            }
        }
        unique.sort(BY_SEQUENCE);

        Order current = order;
        List<Step> steps = new ArrayList<>();
        List<PaymentEvent> waiting = new ArrayList<>();
        for (PaymentEvent event : unique) {
            boolean ready =
                    current.status() == OrderStatus.CANCELLED || event.sequence() <= current.lastEventSequence() + 1;
            if (!ready) {
                waiting.add(event);
                continue;
            }
            try {
                Transition t = machine.apply(current, event);
                steps.add(new Step(event, t, null));
                if (t instanceof Transition.Applied applied) {
                    current = applied.order();
                }
            } catch (IllegalTransitionException e) {
                steps.add(new Step(event, null, e));
            }
        }
        return new Result(current, steps, duplicates, waiting);
    }
}

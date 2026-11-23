package com.specshield.ledgerline.domain;

import static com.specshield.ledgerline.domain.OrderStatus.CANCELLED;
import static com.specshield.ledgerline.domain.OrderStatus.CREATED;
import static com.specshield.ledgerline.domain.OrderStatus.PAID;
import static com.specshield.ledgerline.domain.OrderStatus.PARTIALLY_REFUNDED;
import static com.specshield.ledgerline.domain.OrderStatus.PAYMENT_FAILED;
import static com.specshield.ledgerline.domain.OrderStatus.PAYMENT_PENDING;
import static com.specshield.ledgerline.domain.OrderStatus.REFUNDED;

import java.time.Clock;
import java.time.Instant;

/**
 * The order payment lifecycle.
 *
 * <p>Checks run in this order:
 *
 * <ol>
 *   <li>Order {@code CANCELLED}: every event is {@link Transition.Ignored}.
 *   <li>{@code event.sequence <= order.lastEventSequence}: {@link Transition.Stale} (out of order or replayed).
 *   <li>{@code PAYMENT_FAILED} once the order is {@code PAID}, {@code PARTIALLY_REFUNDED} or {@code REFUNDED}:
 *       {@link Transition.Stale}. A failure cannot undo a payment that already succeeded.
 *   <li>The transition table:
 *       <pre>
 * CREATED            --PAYMENT_INITIATED--> PAYMENT_PENDING
 * PAYMENT_PENDING    --PAYMENT_SUCCEEDED--> PAID
 * PAYMENT_PENDING    --PAYMENT_FAILED-----> PAYMENT_FAILED
 * PAYMENT_FAILED     --PAYMENT_SUCCEEDED--> PAID            (customer retried successfully)
 * PAID               --REFUND_SUCCEEDED---> PARTIALLY_REFUNDED (amount &lt; refundable) or REFUNDED (amount = refundable)
 * PARTIALLY_REFUNDED --REFUND_SUCCEEDED---> PARTIALLY_REFUNDED or REFUNDED, same rule
 *       </pre>
 *   <li>Anything else, including a refund above the refundable amount: {@link IllegalTransitionException}.
 * </ol>
 *
 * <p>The only command is {@link #cancel(Order)}, allowed from {@code CREATED} only.
 */
public final class OrderStateMachine {

    private final Clock clock;

    public OrderStateMachine(Clock clock) {
        this.clock = clock;
    }

    public Transition apply(Order order, PaymentEvent event) {
        if (!order.id().equals(event.orderId())) {
            throw new IllegalArgumentException(
                    "event " + event.providerEventId() + " is for order " + event.orderId() + ", not " + order.id());
        }
        OrderStatus from = order.status();
        if (from == CANCELLED) {
            return new Transition.Ignored("order is CANCELLED; " + event + " ignored");
        }
        if (event.sequence() <= order.lastEventSequence()) {
            return new Transition.Stale(event + " has sequence <= last applied sequence " + order.lastEventSequence());
        }
        if (event.type() == PaymentEventType.PAYMENT_FAILED && from.isPaidOrLater()) {
            return new Transition.Stale(event + " arrived after the payment succeeded (order is " + from + ")");
        }

        return switch (event.type()) {
            case PAYMENT_INITIATED -> {
                requireFrom(from, CREATED, PAYMENT_PENDING, event);
                yield applied(order, PAYMENT_PENDING, order.refundedMinor(), event);
            }
            case PAYMENT_SUCCEEDED -> {
                if (from != PAYMENT_PENDING && from != PAYMENT_FAILED) {
                    throw illegal(from, PAID, event, "payment can only succeed from PAYMENT_PENDING or PAYMENT_FAILED");
                }
                yield applied(order, PAID, order.refundedMinor(), event);
            }
            case PAYMENT_FAILED -> {
                requireFrom(from, PAYMENT_PENDING, PAYMENT_FAILED, event);
                yield applied(order, PAYMENT_FAILED, order.refundedMinor(), event);
            }
            case REFUND_SUCCEEDED -> refund(order, event);
        };
    }

    /**
     * Checks that a payment may be requested: the order is CREATED, or PAYMENT_FAILED (the customer retries). Asking
     * for a payment does not change state; the provider reports progress through events.
     */
    public void requirePayable(Order order) {
        OrderStatus from = order.status();
        if (from != CREATED && from != PAYMENT_FAILED) {
            throw illegal(
                    from,
                    PAYMENT_PENDING,
                    "pay()",
                    "payment can only be requested for a CREATED or PAYMENT_FAILED order");
        }
    }

    /** CREATED to CANCELLED. Any other state throws {@link IllegalTransitionException}. */
    public Order cancel(Order order) {
        if (order.status() != CREATED) {
            throw illegal(order.status(), CANCELLED, "cancel()", "only a CREATED order can be cancelled");
        }
        return order.withStatus(CANCELLED, now());
    }

    private Transition refund(Order order, PaymentEvent event) {
        OrderStatus from = order.status();
        Money refund = Money.usd(event.amountMinor());
        Money refundable = order.refundable();
        OrderStatus to = refund.compareTo(refundable) >= 0 ? REFUNDED : PARTIALLY_REFUNDED;
        if (from != PAID && from != PARTIALLY_REFUNDED) {
            throw illegal(from, to, event, "refunds are only possible from PAID or PARTIALLY_REFUNDED");
        }
        if (refund.isGreaterThan(refundable)) {
            throw illegal(
                    from,
                    to,
                    event,
                    "refund " + refund.amountMinor() + " exceeds refundable amount " + refundable.amountMinor());
        }
        Money refundedAfter = order.refunded().plus(refund);
        return applied(order, to, refundedAfter.amountMinor(), event);
    }

    private Transition applied(Order order, OrderStatus to, long refundedMinor, PaymentEvent event) {
        Order next = order.withPaymentState(to, refundedMinor, event.sequence(), event.occurredAt(), now());
        return new Transition.Applied(to, next);
    }

    private static void requireFrom(OrderStatus from, OrderStatus required, OrderStatus to, PaymentEvent event) {
        if (from != required) {
            throw illegal(from, to, event, event.type() + " is only allowed from " + required);
        }
    }

    private static IllegalTransitionException illegal(OrderStatus from, OrderStatus to, Object event, String reason) {
        return new IllegalTransitionException(from, to, String.valueOf(event), reason);
    }

    private Instant now() {
        return clock.instant();
    }
}

package com.specshield.ledgerline.domain;

import java.util.Objects;

/** Result of applying a {@link PaymentEvent} to an {@link Order}. */
public sealed interface Transition {

    /** The event changed the order. {@code order} is the new state to persist. */
    record Applied(OrderStatus newStatus, Order order) implements Transition {
        public Applied {
            Objects.requireNonNull(order, "order");
            if (order.status() != newStatus) {
                throw new IllegalArgumentException("newStatus must equal order.status()");
            }
        }
    }

    /** The event arrived out of order or is superseded. The order is unchanged. */
    record Stale(String reason) implements Transition {}

    /** The event is irrelevant in the order's state (for example, the order was cancelled). Unchanged. */
    record Ignored(String reason) implements Transition {}
}

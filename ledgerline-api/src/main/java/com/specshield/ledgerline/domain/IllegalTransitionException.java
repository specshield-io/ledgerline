package com.specshield.ledgerline.domain;

/** Thrown when an event or command is not allowed in the order's current state. */
public final class IllegalTransitionException extends RuntimeException {

    private final OrderStatus from;
    private final OrderStatus to;
    private final String event;

    public IllegalTransitionException(OrderStatus from, OrderStatus to, String event, String reason) {
        super("Illegal transition from=" + from + " to=" + to + " event=" + event + ": " + reason);
        this.from = from;
        this.to = to;
        this.event = event;
    }

    public OrderStatus from() {
        return from;
    }

    /** The state the event or command would have led to. */
    public OrderStatus to() {
        return to;
    }

    public String event() {
        return event;
    }
}

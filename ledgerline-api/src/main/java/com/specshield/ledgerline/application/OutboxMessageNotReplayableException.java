package com.specshield.ledgerline.application;

/** Only DEAD messages can be replayed; the others are still being delivered or already were. */
public class OutboxMessageNotReplayableException extends RuntimeException {

    private final OutboxStatus status;

    public OutboxMessageNotReplayableException(long id, OutboxStatus status) {
        super("Outbox message " + id + " is " + status + "; only DEAD messages can be replayed.");
        this.status = status;
    }

    public OutboxStatus status() {
        return status;
    }
}

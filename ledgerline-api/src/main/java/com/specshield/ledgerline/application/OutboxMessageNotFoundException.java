package com.specshield.ledgerline.application;

public class OutboxMessageNotFoundException extends RuntimeException {

    public OutboxMessageNotFoundException(long id) {
        super("Outbox message " + id + " does not exist.");
    }
}

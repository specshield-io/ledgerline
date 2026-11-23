package com.specshield.ledgerline.application;

/** Lifecycle of an outbox row: PENDING -> IN_FLIGHT -> DELIVERED, or back to PENDING for a retry, or DEAD. */
public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    DEAD
}

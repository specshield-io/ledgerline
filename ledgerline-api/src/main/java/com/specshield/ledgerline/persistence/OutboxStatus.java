package com.specshield.ledgerline.persistence;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    DEAD
}

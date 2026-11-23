package com.specshield.ledgerline.application;

import java.util.UUID;

public final class OrderNotFoundException extends RuntimeException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order " + orderId + " does not exist.");
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}

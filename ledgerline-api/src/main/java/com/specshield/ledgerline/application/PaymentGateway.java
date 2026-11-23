package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;

/**
 * Port to the payment provider (MockPay). Asking for a payment does not change the order: the provider reports
 * progress later as PAYMENT_INITIATED / PAYMENT_SUCCEEDED / PAYMENT_FAILED events.
 */
public interface PaymentGateway {

    /** Asks the provider to start collecting payment for the order. Returns the provider's reference. */
    String requestPayment(Order order);
}

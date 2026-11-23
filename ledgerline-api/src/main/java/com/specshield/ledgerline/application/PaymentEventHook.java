package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;

/**
 * Extension point called after an order was changed by provider events and before the transaction commits. The
 * production implementation does nothing; tests use it to inject a failure at the worst moment (LL-I12).
 */
@FunctionalInterface
public interface PaymentEventHook {

    void beforeCommit(Order changed);
}

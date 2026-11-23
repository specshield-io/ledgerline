package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.PaymentEventHook;
import com.specshield.ledgerline.domain.Order;
import java.util.concurrent.atomic.AtomicBoolean;

/** LL-I12: throws once, after the order was changed and before the transaction commits. */
public final class FailureInjectionHook implements PaymentEventHook {

    private final AtomicBoolean armed = new AtomicBoolean();

    public void armOnce() {
        armed.set(true);
    }

    public void disarm() {
        armed.set(false);
    }

    @Override
    public void beforeCommit(Order changed) {
        if (armed.compareAndSet(true, false)) {
            throw new IllegalStateException("injected failure after the state change, before commit");
        }
    }
}

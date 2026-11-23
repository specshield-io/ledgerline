package com.specshield.ledgerline.application;

/** Port for wiping the demo data. Joins the caller's transaction. */
public interface DemoDataStore {

    /**
     * Empties orders, inbox, audit log, outbox and the MockPay ledger. Idempotency keys are kept: they expire on
     * their own, and the request that triggers a reset holds one.
     */
    void truncateDemoTables();

    long countOrders();
}

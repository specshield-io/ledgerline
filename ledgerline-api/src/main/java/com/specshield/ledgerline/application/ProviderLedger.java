package com.specshield.ledgerline.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port to the payment provider's own record of payments, used for reconciliation. */
public interface ProviderLedger {

    /** The latest settled payment per order, settled at or before {@code settledBefore}. */
    List<SettledPayment> latestSettledPayments(Instant settledBefore);

    record SettledPayment(String paymentId, UUID orderId, String status, Instant settledAt) {}
}

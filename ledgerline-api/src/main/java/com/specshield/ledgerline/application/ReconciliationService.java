package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.domain.OrderStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compares the provider's settled payments with our orders and records every difference as an audit row of kind
 * RECONCILIATION_MISMATCH. A mismatch is recorded once per (payment, provider status, order status), not on every
 * run. Payments settled within the grace period are skipped: their webhook may still be in flight.
 */
@Service
public class ReconciliationService {

    public static final String MISMATCH = "RECONCILIATION_MISMATCH";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Set<OrderStatus> PAID_STATES =
            Set.of(OrderStatus.PAID, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED);

    private final ProviderLedger ledger;
    private final OrderStore orders;
    private final AuditTrail audit;
    private final Clock clock;
    private final Duration grace;
    private final JsonMapper json;

    public ReconciliationService(
            ProviderLedger ledger,
            OrderStore orders,
            AuditTrail audit,
            Clock clock,
            @Value("${ledgerline.reconciliation.grace:PT2M}") Duration grace,
            JsonMapper json) {
        this.ledger = ledger;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
        this.grace = grace;
        this.json = json;
    }

    /** Returns the number of new mismatches recorded. */
    @Transactional
    public int reconcile() {
        Instant now = clock.instant();
        int recorded = 0;
        for (ProviderLedger.SettledPayment payment : ledger.latestSettledPayments(now.minus(grace))) {
            Optional<Order> order = orders.findById(payment.orderId());
            String orderStatus = order.map(o -> o.status().name()).orElse("MISSING");
            if (order.isPresent() && consistent(payment.status(), order.get().status())) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("paymentId", payment.paymentId());
            detail.put("providerStatus", payment.status());
            detail.put("orderStatus", orderStatus);
            String detailJson = json.writeValueAsString(detail);
            boolean alreadyRecorded = audit.forOrder(payment.orderId()).stream()
                    .anyMatch(a -> MISMATCH.equals(a.kind())
                            && json.readTree(a.detailJson()).equals(json.readTree(detailJson)));
            if (alreadyRecorded) {
                continue;
            }
            audit.append(new AuditRecord(null, payment.orderId(), MISMATCH, detailJson, "reconciliation", null, now));
            log.warn(
                    "reconciliation mismatch: payment {} is {} at the provider, order {} is {}",
                    payment.paymentId(),
                    payment.status(),
                    payment.orderId(),
                    orderStatus);
            recorded++;
        }
        return recorded;
    }

    static boolean consistent(String providerStatus, OrderStatus orderStatus) {
        return switch (providerStatus) {
            case "SUCCEEDED" -> PAID_STATES.contains(orderStatus);
            case "FAILED" -> orderStatus == OrderStatus.PAYMENT_FAILED;
            default -> true;
        };
    }
}

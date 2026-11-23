package com.specshield.ledgerline.mockpay;

import com.specshield.ledgerline.application.ProviderLedger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * MockPay's own record of payments (table mockpay_ledger): what the provider believes happened. Ledgerline only
 * reads it for reconciliation, as it would read a real provider's settlement report.
 */
@Repository
public class MockPayLedger implements ProviderLedger {

    public enum Status {
        PENDING,
        SUCCEEDED,
        FAILED
    }

    private final JdbcClient jdbc;

    MockPayLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The last event sequence MockPay used for the order, 0 if none. */
    public long lastSequence(UUID orderId) {
        return jdbc.sql("SELECT coalesce(max(last_sequence), 0) FROM mockpay_ledger WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    public void record(
            String paymentId, UUID orderId, long amountMinor, String currency, long lastSequence, Instant at) {
        jdbc.sql("""
                        INSERT INTO mockpay_ledger (payment_id, order_id, amount_minor, currency, status, last_sequence, created_at)
                        VALUES (:paymentId, :orderId, :amount, :currency, 'PENDING', :seq, :at)
                        """)
                .param("paymentId", paymentId)
                .param("orderId", orderId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("seq", lastSequence)
                .param("at", Timestamp.from(at))
                .update();
    }

    public void settle(String paymentId, Status status, Instant at) {
        jdbc.sql("UPDATE mockpay_ledger SET status = :status, settled_at = :at WHERE payment_id = :paymentId")
                .param("status", status.name())
                .param("at", Timestamp.from(at))
                .param("paymentId", paymentId)
                .update();
    }

    @Override
    public List<SettledPayment> latestSettledPayments(Instant settledBefore) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (order_id) payment_id, order_id, status, settled_at
                          FROM mockpay_ledger
                         WHERE status <> 'PENDING' AND settled_at <= :before
                         ORDER BY order_id, created_at DESC
                        """)
                .param("before", Timestamp.from(settledBefore))
                .query((rs, n) -> new SettledPayment(
                        rs.getString("payment_id"),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("status"),
                        rs.getTimestamp("settled_at").toInstant()))
                .list();
    }
}

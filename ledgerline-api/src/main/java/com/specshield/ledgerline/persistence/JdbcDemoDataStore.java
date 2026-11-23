package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.application.DemoDataStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDemoDataStore implements DemoDataStore {

    private final JdbcClient jdbc;

    JdbcDemoDataStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void truncateDemoTables() {
        // One statement, so there is no window with some tables empty and others not. TRUNCATE is transactional in
        // PostgreSQL: readers see the old data until the reset commits.
        jdbc.sql("TRUNCATE orders, order_events_inbox, audit_log, outbox, mockpay_ledger RESTART IDENTITY")
                .update();
    }

    @Override
    public long countOrders() {
        return jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single();
    }
}

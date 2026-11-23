package com.specshield.ledgerline.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.specshield.ledgerline.application.DemoDataService;
import com.specshield.ledgerline.demo.DemoReset;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** LL-N07: the demo data reset is repeatable and never runs twice at once. */
class DemoResetIT extends ApiTestSupport {

    @Autowired
    DemoReset demoReset;

    @Autowired
    LockProvider lockProvider;

    void strayData() {
        String id = createOrder("key-n07-stray-1");
        post("/api/v1/orders/" + id + "/cancel", "key-n07-stray-2").then().statusCode(200);
        jdbc.update("""
                insert into mockpay_ledger (payment_id, order_id, amount_minor, currency, status, last_sequence, created_at)
                values ('mp_stray', ?::uuid, 2599, 'USD', 'PENDING', 1, now())
                """, id);
        jdbc.update("""
                insert into order_events_inbox (provider, provider_event_id, payload, signature_valid, received_at,
                                                processed_at, outcome)
                values ('mockpay', 'rejected_stray', '{}', false, now(), now(), 'REJECTED')
                """);
    }

    void assertSeededState() {
        assertThat(count("orders")).isEqualTo(DemoDataService.SEEDED_ORDERS);
        assertThat(jdbc.queryForList("select merchant_ref from orders order by merchant_ref", String.class))
                .first()
                .isEqualTo("demo-1001");
        assertThat(jdbc.queryForObject("select count(*) from orders where merchant_ref like 'demo-%'", Long.class))
                .isEqualTo(25);
        assertThat(jdbc.queryForObject("select count(distinct merchant_ref) from orders", Long.class))
                .isEqualTo(25);
        assertThat(jdbc.queryForList("select distinct status from orders", String.class))
                .containsExactlyInAnyOrder(
                        "CREATED",
                        "PAYMENT_PENDING",
                        "PAID",
                        "PAYMENT_FAILED",
                        "PARTIALLY_REFUNDED",
                        "REFUNDED",
                        "CANCELLED");
        assertThat(count("order_events_inbox")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("mockpay_ledger")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where kind = 'DEMO_SEEDED'", Long.class))
                .isEqualTo(25);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log a where a.order_id is not null"
                                + " and not exists (select 1 from orders o where o.id = a.order_id)",
                        Long.class))
                .as("no audit rows for orders that no longer exist")
                .isZero();
    }

    @Test
    @DisplayName("LL-N07 resetting twice in a row leaves exactly the 25 seeded orders and no orphan rows")
    void resetTwice() {
        strayData();

        assertThat(demoReset.reset("test:first")).isEqualTo(DemoReset.Outcome.DONE);
        assertSeededState();
        assertThat(demoReset.reset("test:second")).isEqualTo(DemoReset.Outcome.DONE);
        assertSeededState();

        assertThat(jdbc.queryForList("select actor from audit_log where kind = 'DEMO_RESET'", String.class))
                .as("the first reset's audit row went with the data it described")
                .containsExactly("test:second");
        assertThat(jdbc.queryForObject("select count(*) from shedlock where name = ?", Long.class, DemoReset.LOCK))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LL-N07 while the ShedLock lock is held elsewhere, a reset does nothing and the admin endpoint is 409")
    void lockHeld() {
        Optional<SimpleLock> held = lockProvider.lock(
                new LockConfiguration(ClockProvider.now(), DemoReset.LOCK, Duration.ofMinutes(1), Duration.ZERO));
        assertThat(held).as("the test took the lock").isPresent();
        try {
            assertThat(demoReset.reset("test:blocked")).isEqualTo(DemoReset.Outcome.ALREADY_RUNNING);
            assertThat(demoReset.seedIfEmpty()).isEqualTo(DemoReset.Outcome.ALREADY_RUNNING);
            assertThat(count("orders")).isZero();
            asAdmin()
                    .header(IdempotencyFilter.HEADER, "key-n07-locked")
                    .post("/api/v1/admin/reset")
                    .then()
                    .statusCode(409)
                    .contentType("application/problem+json")
                    .body("type", equalTo("urn:ledgerline:problem:reset-in-progress"));
        } finally {
            held.get().unlock();
        }

        assertThat(demoReset.reset("test:after")).isEqualTo(DemoReset.Outcome.DONE);
        assertSeededState();
    }

    @Test
    @DisplayName("LL-N07 two resets started together end with exactly 25 seeded orders")
    void concurrentResets() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DemoReset.Outcome>> outcomes;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            outcomes = List.of(
                    pool.submit(() -> {
                        start.await();
                        return demoReset.reset("test:a");
                    }),
                    pool.submit(() -> {
                        start.await();
                        return demoReset.reset("test:b");
                    }));
            start.countDown();
        }
        assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get())).contains(DemoReset.Outcome.DONE);
        assertSeededState();
    }

    @Test
    @DisplayName("startup seeding fills an empty database once and leaves existing data alone")
    void seedIfEmpty() {
        assertThat(demoReset.seedIfEmpty()).isEqualTo(DemoReset.Outcome.DONE);
        assertSeededState();
        assertThat(demoReset.seedIfEmpty()).isEqualTo(DemoReset.Outcome.DONE);
        assertThat(count("orders")).as("not seeded again").isEqualTo(25);

        jdbc.execute("TRUNCATE orders, audit_log");
        createOrder("key-n07-existing");
        demoReset.seedIfEmpty();
        assertThat(count("orders")).as("a non-empty database is not seeded").isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/v1/admin/reset as ADMIN resets and names the user in the audit log")
    void adminEndpoint() {
        strayData();

        asAdmin()
                .header(IdempotencyFilter.HEADER, "key-n07-admin")
                .post("/api/v1/admin/reset")
                .then()
                .statusCode(200)
                .body("seededOrders", equalTo(25));

        assertSeededState();
        assertThat(jdbc.queryForObject("select actor from audit_log where kind = 'DEMO_RESET'", String.class))
                .isEqualTo("admin:" + ADMIN_USER);
    }
}

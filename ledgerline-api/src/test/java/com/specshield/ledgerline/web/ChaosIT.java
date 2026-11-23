package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import com.specshield.ledgerline.chaos.ChaosService;
import com.specshield.ledgerline.outbox.OutboxDispatcher;
import com.specshield.ledgerline.webhook.MerchantSink;
import io.restassured.response.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/** LL-I21/LL-I22: every chaos scenario over real HTTP, with MockPay on. Synthetic data only. */
@TestPropertySource(properties = "ledgerline.mockpay.enabled=true")
@Import(ChaosIT.HookConfig.class)
class ChaosIT extends ApiTestSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class HookConfig {
        @Bean
        @Primary
        BlockingScenarioHook blockingScenarioHook() {
            return new BlockingScenarioHook();
        }
    }

    @Autowired
    BlockingScenarioHook hook;

    @Autowired
    ChaosService chaos;

    @Autowired
    OutboxDispatcher dispatcher;

    @AfterEach
    void releaseRuns() {
        hook.release();
        await().atMost(Duration.ofSeconds(20)).until(() -> chaos.availableSlots() == 3);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    record Started(String runId, String orderId) {}

    static Response startScenario(String name) {
        return post("/api/v1/chaos/scenarios/" + name, "key-chaos-" + UUID.randomUUID());
    }

    /** Starts a scenario and waits until its run has finished. */
    Started run(String name) {
        Response response = startScenario(name);
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(202);
        Started started = new Started(response.path("scenarioRunId"), response.path("orderId"));
        assertThat(response.header("Location")).isEqualTo("/api/v1/chaos/runs/" + started.runId());
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> !"RUNNING".equals(runView(started.runId()).path("status")));
        assertThat(runView(started.runId()).<String>path("status")).isEqualTo("COMPLETED");
        return started;
    }

    static Response runView(String runId) {
        return given().get("/api/v1/chaos/runs/" + runId);
    }

    String status(String orderId) {
        return jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, orderId);
    }

    long inboxRows(String orderId, String outcome) {
        return jdbc.queryForObject(
                "select count(*) from order_events_inbox where order_id = ?::uuid and outcome = ?",
                Long.class,
                orderId,
                outcome);
    }

    long auditRows(String orderId, String kind) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where order_id = ?::uuid and kind = ?", Long.class, orderId, kind);
    }

    long rejected(String reason) {
        return jdbc.queryForObject(
                "select count(*) from order_events_inbox where outcome = 'REJECTED' and payload->>'reason' = ?",
                Long.class,
                reason);
    }

    List<Integer> deliveryStatuses(String runId) {
        List<Integer> statuses = new ArrayList<>();
        for (Object step : runView(runId).<List<?>>path("steps")) {
            Object status = ((java.util.Map<?, ?>) step).get("httpStatus");
            if (status != null) {
                statuses.add((Integer) status);
            }
        }
        return statuses;
    }

    // ---- LL-I21 -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("LL-I21 happy-path: create and pay through MockPay; ends PAID")
    void happyPath() {
        Started run = run("happy-path");

        assertThat(status(run.orderId())).isEqualTo("PAID");
        assertThat(runView(run.runId()).<String>path("orderStatus")).isEqualTo("PAID");
        assertThat(inboxRows(run.orderId(), "APPLIED")).isEqualTo(2);
        assertThat(auditRows(run.orderId(), "CHAOS_STEP")).isGreaterThanOrEqualTo(3);
        given().get("/api/v1/orders/" + run.orderId() + "/timeline")
                .then()
                .statusCode(200)
                .body("find { it.kind == 'CHAOS_STEP' }.actor", equalTo("chaos:happy-path"));
    }

    @Test
    @DisplayName("LL-I21 duplicate-storm: 5 concurrent deliveries of one event; PAID, applied once, 4 DUPLICATE")
    void duplicateStorm() {
        Started run = run("duplicate-storm");

        assertThat(status(run.orderId())).isEqualTo("PAID");
        assertThat(inboxRows(run.orderId(), "APPLIED"))
                .as("INITIATED and SUCCEEDED, once each")
                .isEqualTo(2);
        // ADR 0002: each event is stored once; the four repeats are recorded as DUPLICATE audit rows.
        assertThat(auditRows(run.orderId(), "DUPLICATE")).isEqualTo(4);
        assertThat(deliveryStatuses(run.runId())).containsOnly(200).hasSize(6);
        assertThat(jdbc.queryForObject("select version from orders where id = ?::uuid", Long.class, run.orderId()))
                .as("two state changes only")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("LL-I21 tampered-webhook: body altered after signing is REJECTED; stays PAYMENT_PENDING")
    void tamperedWebhook() {
        Started run = run("tampered-webhook");

        assertThat(status(run.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(rejected("no_matching_signature")).isEqualTo(1);
        assertThat(deliveryStatuses(run.runId())).containsExactly(200, 400);
        assertThat(runView(run.runId()).<List<String>>path("steps.result")).contains("invalid-signature");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where order_id = ?::uuid and kind = 'CHAOS_STEP'"
                                + " and detail->>'result' = 'invalid-signature'",
                        Long.class,
                        run.orderId()))
                .as("the console labels this step Signature rejected")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LL-I21 replay-attack: valid signature 10 minutes old is REJECTED; stays PAYMENT_PENDING")
    void replayAttack() {
        Started run = run("replay-attack");

        assertThat(status(run.orderId())).isEqualTo("PAYMENT_PENDING");
        assertThat(rejected("timestamp_out_of_tolerance")).isEqualTo(1);
        assertThat(deliveryStatuses(run.runId())).containsExactly(200, 400);
    }

    @Test
    @DisplayName("LL-I21 out-of-order: SUCCEEDED seq 2, then FAILED seq 1; ends PAID with one STALE row")
    void outOfOrder() {
        Started run = run("out-of-order");

        assertThat(status(run.orderId())).isEqualTo("PAID");
        assertThat(inboxRows(run.orderId(), "STALE")).isEqualTo(1);
        assertThat(inboxRows(run.orderId(), "APPLIED")).isEqualTo(2);
        assertThat(deliveryStatuses(run.runId())).containsExactly(200, 200, 200);
    }

    @Test
    @DisplayName("LL-I21 merchant-down: order.paid is retried while the merchant is down, DELIVERED after recovery")
    void merchantDown() {
        Instant start = clock.instant();
        Started run = run("merchant-down");
        assertThat(status(run.orderId())).isEqualTo("PAID");
        assertThat(merchantSink.mode()).isEqualTo(MerchantSink.Mode.FAIL);

        dispatcher.dispatchOnce(); // attempt 1: 500
        clock.advance(Duration.between(clock.instant(), nextAttemptAt(run.orderId())));
        dispatcher.dispatchOnce(); // attempt 2, still inside the 2-minute outage: 500
        assertThat(outbox(run.orderId(), "status")).isEqualTo("PENDING");
        assertThat(outbox(run.orderId(), "attempts")).isEqualTo("2");
        assertThat(outbox(run.orderId(), "last_error")).isEqualTo("HTTP 500 from merchant endpoint");

        clock.advance(Duration.between(clock.instant(), start.plus(Duration.ofMinutes(2))));
        assertThat(merchantSink.mode()).as("recovered after 2 minutes").isEqualTo(MerchantSink.Mode.OK);
        if (nextAttemptAt(run.orderId()).isAfter(clock.instant())) {
            clock.advance(Duration.between(clock.instant(), nextAttemptAt(run.orderId())));
        }
        dispatcher.dispatchOnce(); // attempt 3: delivered

        assertThat(outbox(run.orderId(), "status")).isEqualTo("DELIVERED");
        assertThat(outbox(run.orderId(), "attempts")).isEqualTo("3");
        assertThat(merchantSink.deliveries())
                .extracting(MerchantSink.Delivery::responseStatus)
                .containsExactly(200, 500, 500);
    }

    @Test
    @DisplayName("LL-I21 merchant-slow: the merchant answers in 10 s for 2 minutes; the order is PAID")
    void merchantSlow() {
        Started run = run("merchant-slow");

        assertThat(status(run.orderId())).isEqualTo("PAID");
        assertThat(merchantSink.mode()).isEqualTo(MerchantSink.Mode.SLOW);
        assertThat(merchantSink.slowDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(merchantSink.modeUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
        assertThat(outbox(run.orderId(), "event_type")).isEqualTo("order.paid");
        clock.advance(Duration.ofMinutes(2));
        assertThat(merchantSink.mode()).isEqualTo(MerchantSink.Mode.OK);
    }

    @Test
    @DisplayName("LL-I21 refund-flow: pay, partial refund, full refund; ends REFUNDED with a notification for each")
    void refundFlow() {
        Started run = run("refund-flow");

        assertThat(status(run.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                        "select refunded_minor = amount_minor from orders where id = ?::uuid",
                        Boolean.class,
                        run.orderId()))
                .isTrue();
        assertThat(jdbc.queryForList(
                        "select event_type from outbox where aggregate_id = ?::uuid order by id",
                        String.class,
                        run.orderId()))
                .containsExactly("order.paid", "order.partially_refunded", "order.refunded");
    }

    // ---- LL-I22 and the API -----------------------------------------------------------------------------------

    @Test
    @DisplayName("LL-I22 a fourth concurrent scenario run is refused with 429; a slot frees when a run ends")
    void concurrencyCap() {
        hook.arm();
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Response started = startScenario("happy-path");
            assertThat(started.statusCode()).isEqualTo(202);
            runs.add(started.path("scenarioRunId"));
        }
        assertThat(chaos.availableSlots()).isZero();

        Response fourth = startScenario("out-of-order");
        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(fourth.contentType()).startsWith("application/problem+json");
        assertThat(fourth.<String>path("type")).isEqualTo("urn:ledgerline:problem:too-many-scenario-runs");
        assertThat(fourth.header("Retry-After")).isEqualTo("5");
        assertThat(count("orders")).as("no order created for the refused run").isEqualTo(3);

        hook.release();
        for (String id : runs) {
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> "COMPLETED".equals(runView(id).path("status")));
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> chaos.availableSlots() == 3);
        assertThat(startScenario("out-of-order").statusCode()).isEqualTo(202);
    }

    @Test
    @DisplayName("a repeated start with the same Idempotency-Key returns the same run, not a new one")
    void idempotentStart() {
        Response first = post("/api/v1/chaos/scenarios/out-of-order", "key-chaos-same-0001");
        Response again = post("/api/v1/chaos/scenarios/out-of-order", "key-chaos-same-0001");

        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(again.header(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(again.<String>path("scenarioRunId")).isEqualTo(first.path("scenarioRunId"));
        await().atMost(Duration.ofSeconds(20))
                .until(() ->
                        "COMPLETED".equals(runView(first.path("scenarioRunId")).path("status")));
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown run id is 404 problem+json")
    void unknownRun() {
        given().get("/api/v1/chaos/runs/nope")
                .then()
                .statusCode(404)
                .body("type", equalTo("urn:ledgerline:problem:scenario-run-not-found"));
    }

    private Instant nextAttemptAt(String orderId) {
        return jdbc.queryForObject(
                        "select next_attempt_at from outbox where aggregate_id = ?::uuid",
                        java.sql.Timestamp.class,
                        orderId)
                .toInstant();
    }

    private String outbox(String orderId, String column) {
        return jdbc.queryForObject(
                "select " + column + "::text from outbox where aggregate_id = ?::uuid", String.class, orderId);
    }
}

package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.specshield.ledgerline.application.ReconciliationService;
import com.specshield.ledgerline.domain.Money;
import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStore;
import com.specshield.ledgerline.mockpay.MockPayLedger;
import com.specshield.ledgerline.webhook.MockPayWebhookController;
import com.specshield.ledgerline.webhook.OrphanReprocessor;
import com.specshield.ledgerline.webhook.TestSigner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The hardened MockPay webhook receiver, called directly with test-signed deliveries. Synthetic data only. */
@ExtendWith(OutputCaptureExtension.class)
class MockPayWebhookIT extends ApiTestSupport {

    @Autowired
    MeterRegistry meters;

    @Autowired
    OrphanReprocessor orphanReprocessor;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    MockPayLedger ledger;

    @Autowired
    OrderStore orderStore;

    // ---- helpers ----------------------------------------------------------------------------------------------

    static String event(String id, String type, long sequence, String orderId) {
        return """
                {"id":"%s","type":"%s","created":%d,"sequence":%d,"data":{"orderId":"%s","amountMinor":2599,"currency":"USD"}}""".formatted(id, type, MutableClockStart.EPOCH, sequence, orderId);
    }

    /** Holder so the text block above can use a constant epoch second. */
    static final class MutableClockStart {
        static final long EPOCH = com.specshield.ledgerline.MutableClock.START.getEpochSecond();
    }

    Response deliver(String body) {
        return deliver(body, TestSigner.CURRENT_SECRET);
    }

    Response deliver(String body, String secret) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return given().contentType("application/json")
                .header(
                        MockPayWebhookController.SIGNATURE_HEADER,
                        TestSigner.header(secret, clock.instant().getEpochSecond(), bytes))
                .body(bytes)
                .post(MockPayWebhookController.PATH);
    }

    String pendingOrder() {
        String id = createOrder("key-wh-" + UUID.randomUUID().toString().substring(0, 12));
        deliver(event("evt_init_" + id.replace("-", ""), "PAYMENT_INITIATED", 1, id))
                .then()
                .statusCode(200);
        assertThat(status(id)).isEqualTo("PAYMENT_PENDING");
        return id;
    }

    String status(String orderId) {
        return jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, orderId);
    }

    String outcome(String eventId) {
        List<String> rows = jdbc.queryForList(
                "select coalesce(outcome, 'PENDING') from order_events_inbox where provider_event_id = ?",
                String.class,
                eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    long auditCount(String kind) {
        return jdbc.queryForObject("select count(*) from audit_log where kind = ?", Long.class, kind);
    }

    double counter(String name, String... tags) {
        Counter c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "LL-I06 a valid PAYMENT_SUCCEEDED for a PAYMENT_PENDING order: 200, PAID, inbox APPLIED, audit and outbox rows")
    void appliesSucceeded() {
        String orderId = pendingOrder();
        double appliedBefore = counter("ledgerline.webhook.applied");

        deliver(event("evt_i06_ok", "PAYMENT_SUCCEEDED", 2, orderId))
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("received", equalTo(true))
                .body("duplicate", nullValue());

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(outcome("evt_i06_ok")).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where order_id = ?::uuid and kind = 'PAYMENT_EVENT_APPLIED'"
                                + " and detail->>'to' = 'PAID' and detail->>'providerEventId' = 'evt_i06_ok'",
                        Long.class,
                        orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox where aggregate_id = ?::uuid and status = 'PENDING'"
                                + " and event_type = 'order.paid' and payload->>'status' = 'PAID'"
                                + " and payload->>'previousStatus' = 'PAYMENT_PENDING'",
                        Long.class,
                        orderId))
                .isEqualTo(1);
        assertThat(counter("ledgerline.webhook.applied") - appliedBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select signature_valid from order_events_inbox where provider_event_id = 'evt_i06_ok'",
                        Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName(
            "LL-I07 the same event delivered twice: second response duplicate=true, no second state change, counter 1")
    void duplicateDelivery() {
        String orderId = pendingOrder();
        String body = event("evt_i07_dup", "PAYMENT_SUCCEEDED", 2, orderId);
        double before = counter("ledgerline.webhook.duplicates");

        deliver(body).then().statusCode(200).body("duplicate", nullValue());
        long versionAfterFirst =
                jdbc.queryForObject("select version from orders where id = ?::uuid", Long.class, orderId);
        deliver(body).then().statusCode(200).body("received", equalTo(true)).body("duplicate", equalTo(true));

        assertThat(counter("ledgerline.webhook.duplicates") - before).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from orders where id = ?::uuid", Long.class, orderId))
                .isEqualTo(versionAfterFirst);
        assertThat(jdbc.queryForObject(
                        "select count(*) from order_events_inbox where provider_event_id = 'evt_i07_dup'", Long.class))
                .isEqualTo(1);
        assertThat(auditCount("DUPLICATE")).isEqualTo(1);
        assertThat(auditCount("PAYMENT_EVENT_APPLIED")).isEqualTo(2); // INITIATED + SUCCEEDED, not 3
    }

    @Test
    @DisplayName(
            "LL-I08 50 concurrent deliveries of one event: one transition, one inbox row, 49 duplicates, no errors")
    void concurrentDeliveries() throws Exception {
        String orderId = pendingOrder();
        byte[] body = event("evt_i08_race", "PAYMENT_SUCCEEDED", 2, orderId).getBytes(StandardCharsets.UTF_8);
        String signature =
                TestSigner.header(TestSigner.CURRENT_SECRET, clock.instant().getEpochSecond(), body);
        double duplicatesBefore = counter("ledgerline.webhook.duplicates");
        double failuresBefore = counter("ledgerline.webhook.failures");
        int deliveries = 50;

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + MockPayWebhookController.PATH))
                .header("Content-Type", "application/json")
                .header(MockPayWebhookController.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        CountDownLatch ready = new CountDownLatch(deliveries);
        CountDownLatch go = new CountDownLatch(1);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(deliveries)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < deliveries; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<HttpResponse<String>> f : futures) {
                responses.add(f.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(responses)
                .filteredOn(r -> r.body().contains("\"duplicate\":true"))
                .hasSize(deliveries - 1);
        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select version from orders where id = ?::uuid", Long.class, orderId))
                .as("INITIATED and SUCCEEDED: exactly two updates")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from order_events_inbox where provider_event_id = 'evt_i08_race'", Long.class))
                .isEqualTo(1);
        assertThat(counter("ledgerline.webhook.duplicates") - duplicatesBefore).isEqualTo(deliveries - 1);
        assertThat(counter("ledgerline.webhook.failures") - failuresBefore).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id = ?::uuid", Long.class, orderId))
                .as("one order.paid notification; PAYMENT_PENDING is not announced")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LL-I09 invalid signature: 400, inbox REJECTED row, order unchanged, signature never echoed or logged")
    void invalidSignature(CapturedOutput output) {
        String orderId = pendingOrder();
        String body = event("evt_i09_forged", "PAYMENT_SUCCEEDED", 2, orderId);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String forged = TestSigner.header(
                "an-attacker-guessed-secret-of-enough-length", clock.instant().getEpochSecond(), bytes);
        double rejectedBefore = counter("ledgerline.webhook.rejected", "reason", "no_matching_signature");

        Response response = given().contentType("application/json")
                .header(MockPayWebhookController.SIGNATURE_HEADER, forged)
                .body(bytes)
                .post(MockPayWebhookController.PATH);

        response.then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:invalid-signature"));
        String v1 = forged.substring(forged.indexOf("v1=") + 3);
        assertThat(response.asString()).doesNotContain(v1).doesNotContain(forged);
        assertThat(output.getAll()).doesNotContain(v1).doesNotContain(TestSigner.CURRENT_SECRET);

        assertThat(status(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(outcome("evt_i09_forged"))
                .as("the untrusted event id is not used as the inbox key")
                .isNull();
        Map<String, Object> rejected = jdbc.queryForMap(
                "select provider, provider_event_id, signature_valid, outcome, payload::text as payload, processed_at"
                        + " from order_events_inbox where outcome = 'REJECTED'");
        assertThat(rejected.get("signature_valid")).isEqualTo(false);
        assertThat((String) rejected.get("provider_event_id")).startsWith("rejected_");
        assertThat((String) rejected.get("payload"))
                .contains("no_matching_signature")
                .contains("bodySha256")
                .doesNotContain(v1);
        assertThat(rejected.get("processed_at")).isNotNull();
        assertThat(auditCount("SECURITY_REJECTED")).isEqualTo(1);
        assertThat(counter("ledgerline.webhook.rejected", "reason", "no_matching_signature") - rejectedBefore)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LL-I09 missing, malformed and expired signatures are rejected with a reason-tagged metric")
    void rejectionReasons() {
        String orderId = pendingOrder();
        byte[] body = event("evt_i09_r", "PAYMENT_SUCCEEDED", 2, orderId).getBytes(StandardCharsets.UTF_8);
        long now = clock.instant().getEpochSecond();
        Map<String, String> cases = Map.of(
                "missing_header", "",
                "malformed_header", "t=abc",
                "timestamp_out_of_tolerance", TestSigner.header(TestSigner.CURRENT_SECRET, now - 301, body));
        cases.forEach((reason, header) -> {
            double before = counter("ledgerline.webhook.rejected", "reason", reason);
            var request = given().contentType("application/json").body(body);
            if (!header.isEmpty()) {
                request = request.header(MockPayWebhookController.SIGNATURE_HEADER, header);
            }
            request.post(MockPayWebhookController.PATH)
                    .then()
                    .statusCode(400)
                    .body("type", equalTo("urn:ledgerline:problem:invalid-signature"));
            assertThat(counter("ledgerline.webhook.rejected", "reason", reason) - before)
                    .as(reason)
                    .isEqualTo(1);
        });
        assertThat(status(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(auditCount("SECURITY_REJECTED")).isEqualTo(3);
    }

    @Test
    @DisplayName("an event signed with the previous secret is accepted during rotation")
    void rotationSecretAccepted() {
        String orderId = pendingOrder();

        deliver(event("evt_rot", "PAYMENT_SUCCEEDED", 2, orderId), TestSigner.PREVIOUS_SECRET)
                .then()
                .statusCode(200);

        assertThat(status(orderId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("LL-I10 an unknown event type returns 200 and is IGNORED")
    void unknownType() {
        String orderId = pendingOrder();

        deliver(event("evt_i10_unknown", "PAYMENT_DISPUTED", 2, orderId))
                .then()
                .statusCode(200)
                .body("received", equalTo(true));

        assertThat(outcome("evt_i10_unknown")).isEqualTo("IGNORED");
        assertThat(status(orderId)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    @DisplayName("a validly signed body that is not a MockPay event is 400 malformed-event and stores nothing")
    void malformedEvent() {
        deliver("{\"hello\":\"world\"}")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:malformed-event"));

        assertThat(count("order_events_inbox")).isZero();
    }

    @Test
    @DisplayName("LL-I11 an event before its order exists is ORPHAN; once the order exists the reprocessor applies it")
    void orphanThenApplied() {
        UUID orderId = UUID.randomUUID();

        deliver(event("evt_i11_early", "PAYMENT_INITIATED", 1, orderId.toString()))
                .then()
                .statusCode(200);
        assertThat(outcome("evt_i11_early")).isEqualTo("ORPHAN");
        assertThat(orphanReprocessor.run()).as("order still missing").isZero();

        orderStore.insert(Order.create(orderId, "synthetic-late-order", 2599, Money.USD, clock.instant()));
        clock.advance(Duration.ofSeconds(30));
        assertThat(orphanReprocessor.run()).isEqualTo(1);

        assertThat(outcome("evt_i11_early")).isEqualTo("APPLIED");
        assertThat(status(orderId.toString())).isEqualTo("PAYMENT_PENDING");
        assertThat(orphanReprocessor.run()).as("nothing left to retry").isZero();
    }

    @Test
    @DisplayName("LL-I11 orphans older than the retry window are no longer retried")
    void orphanWindow() {
        UUID orderId = UUID.randomUUID();
        deliver(event("evt_i11_old", "PAYMENT_INITIATED", 1, orderId.toString()))
                .then()
                .statusCode(200);
        orderStore.insert(Order.create(orderId, "synthetic-late-order", 2599, Money.USD, clock.instant()));

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThat(orphanReprocessor.run()).isZero();
        assertThat(outcome("evt_i11_old")).isEqualTo("ORPHAN");
    }

    @Test
    @DisplayName(
            "LL-I12 a failure after the state change but before commit: 500, nothing committed, redelivery succeeds")
    void failureRollsBack() {
        String orderId = pendingOrder();
        String body = event("evt_i12_boom", "PAYMENT_SUCCEEDED", 2, orderId);
        double failuresBefore = counter("ledgerline.webhook.failures");
        long auditBefore = count("audit_log");

        failureHook.armOnce();
        deliver(body)
                .then()
                .statusCode(500)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:internal-error"));

        assertThat(status(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(outcome("evt_i12_boom")).as("no inbox row committed").isNull();
        assertThat(count("audit_log")).isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("select count(*) from outbox where payload->>'status' = 'PAID'", Long.class))
                .isZero();
        assertThat(counter("ledgerline.webhook.failures") - failuresBefore).isEqualTo(1);

        deliver(body).then().statusCode(200).body("duplicate", nullValue());
        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(outcome("evt_i12_boom")).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("an event that arrives before its predecessor waits, then both apply in sequence")
    void outOfOrderArrival() {
        String orderId = createOrder("key-wh-ooo-0001");

        deliver(event("evt_ooo_2", "PAYMENT_SUCCEEDED", 2, orderId)).then().statusCode(200);
        assertThat(outcome("evt_ooo_2")).isEqualTo("PENDING");
        assertThat(status(orderId)).isEqualTo("CREATED");

        deliver(event("evt_ooo_1", "PAYMENT_INITIATED", 1, orderId)).then().statusCode(200);
        assertThat(outcome("evt_ooo_1")).isEqualTo("APPLIED");
        assertThat(outcome("evt_ooo_2")).isEqualTo("APPLIED");
        assertThat(status(orderId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a stale event (lower sequence) is recorded STALE and counted, state unchanged")
    void staleEvent() {
        String orderId = pendingOrder();
        deliver(event("evt_st_3", "PAYMENT_SUCCEEDED", 3, orderId));
        deliver(event("evt_st_2", "PAYMENT_SUCCEEDED", 2, orderId)).then().statusCode(200);
        // evt_st_3 waited for seq 2; seq 2 applied, then 3 found PAID+SUCCEEDED illegal -> FAILED
        assertThat(outcome("evt_st_2")).isEqualTo("APPLIED");
        assertThat(outcome("evt_st_3")).isEqualTo("FAILED");

        double staleBefore = counter("ledgerline.webhook.stale");
        deliver(event("evt_st_old", "PAYMENT_FAILED", 1, orderId)).then().statusCode(200);
        assertThat(outcome("evt_st_old")).isEqualTo("STALE");
        assertThat(counter("ledgerline.webhook.stale") - staleBefore).isEqualTo(1);
        assertThat(status(orderId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("LL-I19 reconciliation flags a mismatch created by manually altering an order status, once")
    void reconciliationMismatch() {
        String orderId = pendingOrder();
        deliver(event("evt_i19_ok", "PAYMENT_SUCCEEDED", 2, orderId)).then().statusCode(200);
        ledger.record("mp_i19", UUID.fromString(orderId), 2599, "USD", 2, clock.instant());
        ledger.settle("mp_i19", MockPayLedger.Status.SUCCEEDED, clock.instant());

        clock.advance(Duration.ofMinutes(3));
        assertThat(reconciliation.reconcile())
                .as("provider SUCCEEDED and order PAID agree")
                .isZero();

        jdbc.update("update orders set status = 'PAYMENT_FAILED' where id = ?::uuid", orderId);
        assertThat(reconciliation.reconcile()).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap(
                "select detail->>'providerStatus' as provider, detail->>'orderStatus' as ours, detail->>'paymentId' as payment"
                        + " from audit_log where kind = 'RECONCILIATION_MISMATCH' and order_id = ?::uuid",
                orderId);
        assertThat(audit)
                .containsEntry("provider", "SUCCEEDED")
                .containsEntry("ours", "PAYMENT_FAILED")
                .containsEntry("payment", "mp_i19");

        assertThat(reconciliation.reconcile())
                .as("the same mismatch is not recorded twice")
                .isZero();
    }

    @Test
    @DisplayName("LL-I19 payments settled within the grace period are not reconciled yet")
    void reconciliationGrace() {
        String orderId = createOrder("key-wh-grace-0001");
        ledger.record("mp_grace", UUID.fromString(orderId), 2599, "USD", 2, clock.instant());
        ledger.settle("mp_grace", MockPayLedger.Status.SUCCEEDED, clock.instant());

        assertThat(reconciliation.reconcile()).isZero();
        clock.advance(Duration.ofMinutes(3));
        assertThat(reconciliation.reconcile())
                .as("order still CREATED while the provider says SUCCEEDED")
                .isEqualTo(1);
    }
}

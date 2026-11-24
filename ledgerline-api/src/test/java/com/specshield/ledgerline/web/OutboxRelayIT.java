package com.specshield.ledgerline.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.application.OutboxStatus;
import com.specshield.ledgerline.application.OutboxStore;
import com.specshield.ledgerline.outbox.BackoffPolicy;
import com.specshield.ledgerline.outbox.MerchantDeliveryClient;
import com.specshield.ledgerline.outbox.MerchantProperties;
import com.specshield.ledgerline.outbox.OutboxDispatcher;
import com.specshield.ledgerline.outbox.OutboxMetrics;
import com.specshield.ledgerline.outbox.OutboxProperties;
import com.specshield.ledgerline.outbox.OutboxWatchdog;
import com.specshield.ledgerline.webhook.MockPayWebhookController;
import com.specshield.ledgerline.webhook.TestSigner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The outbox relay against a WireMock merchant endpoint, with the mutable clock. Synthetic data only. */
class OutboxRelayIT extends ApiTestSupport {

    static final String MERCHANT_PATH = "/merchant/notifications";

    static final WireMockServer merchant =
            new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(64));

    static {
        merchant.start();
    }

    @DynamicPropertySource
    static void merchantUrl(DynamicPropertyRegistry registry) {
        registry.add("ledgerline.merchant.url", () -> merchant.baseUrl() + MERCHANT_PATH);
    }

    @AfterAll
    static void stopMerchant() {
        merchant.stop();
    }

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    OutboxWatchdog watchdog;

    @Autowired
    OutboxStore store;

    @Autowired
    MerchantDeliveryClient client;

    @Autowired
    OutboxMetrics metrics;

    @Autowired
    OutboxProperties properties;

    @Autowired
    MeterRegistry meters;

    @Autowired
    io.micrometer.observation.ObservationRegistry observations;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @BeforeEach
    void resetMerchant() {
        merchant.resetAll();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    void merchantAnswers(int status) {
        merchant.stubFor(
                WireMock.post(urlEqualTo(MERCHANT_PATH)).willReturn(aResponse().withStatus(status)));
    }

    /** An order cancelled through the API: commits exactly one order.cancelled outbox row. */
    long cancelledOrderNotification() {
        String orderId = createOrder("key-ob-" + UUID.randomUUID().toString().substring(0, 12));
        post(
                        "/api/v1/orders/" + orderId + "/cancel",
                        "key-oc-" + UUID.randomUUID().toString().substring(0, 12))
                .then()
                .statusCode(200);
        return jdbc.queryForObject("select id from outbox where aggregate_id = ?::uuid", Long.class, orderId);
    }

    OutboxMessage row(long id) {
        return store.page(java.util.Optional.empty(), 0, 100).items().stream()
                .filter(m -> m.id() == id)
                .findFirst()
                .orElseThrow();
    }

    /** A dispatcher whose backoff uses a seeded generator, so the test can predict every delay. */
    OutboxDispatcher seededDispatcher(long seed) {
        return new OutboxDispatcher(store, client, seededPolicy(seed), metrics, observations, clock, properties);
    }

    BackoffPolicy seededPolicy(long seed) {
        return new BackoffPolicy(
                properties.backoffBase(),
                properties.backoffCap(),
                properties.maxAttempts(),
                new SplittableRandom(seed));
    }

    /** Fails a row 8 times (merchant answering 500), advancing the clock to each retry. */
    void driveToDead(long id, OutboxDispatcher dispatcher) {
        for (int attempt = 1; attempt <= 8; attempt++) {
            clock.advance(Duration.between(clock.instant(), row(id).nextAttemptAt()));
            assertThat(dispatcher.dispatchOnce()).as("attempt %d", attempt).isEqualTo(1);
        }
    }

    double counter(String name, String... tags) {
        Counter c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    static String event(String id, String type, long sequence, String orderId, long created) {
        return """
                {"id":"%s","type":"%s","created":%d,"sequence":%d,"data":{"orderId":"%s","amountMinor":2599,"currency":"USD"}}""".formatted(id, type, created, sequence, orderId);
    }

    Response deliverWebhook(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return given().contentType("application/json")
                .header(
                        MockPayWebhookController.SIGNATURE_HEADER,
                        TestSigner.header(
                                TestSigner.CURRENT_SECRET, clock.instant().getEpochSecond(), bytes))
                .body(bytes)
                .post(MockPayWebhookController.PATH);
    }

    long outboxRows(String orderId) {
        return jdbc.queryForObject("select count(*) from outbox where aggregate_id = ?::uuid", Long.class, orderId);
    }

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("LL-I13 a rolled-back state change leaves no outbox row; the committed one leaves exactly one")
    void atomicity() {
        String orderId = createOrder("key-i13-order");
        long created = clock.instant().getEpochSecond();
        deliverWebhook(event("evt_i13_init", "PAYMENT_INITIATED", 1, orderId, created))
                .then()
                .statusCode(200);
        assertThat(outboxRows(orderId)).as("PAYMENT_PENDING is not announced").isZero();
        String succeeded = event("evt_i13_paid", "PAYMENT_SUCCEEDED", 2, orderId, created);

        failureHook.armOnce(); // throws after the order and outbox writes, before commit
        deliverWebhook(succeeded).then().statusCode(500);

        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, orderId))
                .isEqualTo("PAYMENT_PENDING");
        assertThat(outboxRows(orderId)).as("rolled back with the order change").isZero();

        deliverWebhook(succeeded).then().statusCode(200);

        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, orderId))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForList(
                        "select event_type from outbox where aggregate_id = ?::uuid", String.class, orderId))
                .containsExactly("order.paid");

        deliverWebhook(succeeded).then().statusCode(200).body("duplicate", equalTo(true));
        assertThat(outboxRows(orderId)).as("a duplicate delivery adds nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("LL-I13 a committed cancel writes exactly one order.cancelled row; a rejected cancel writes none")
    void atomicityForApiCommands() {
        String orderId = createOrder("key-i13-cancel");
        post("/api/v1/orders/" + orderId + "/cancel", "key-i13-cancel-1").then().statusCode(200);
        post("/api/v1/orders/" + orderId + "/cancel", "key-i13-cancel-2").then().statusCode(409);

        assertThat(jdbc.queryForList(
                        "select event_type from outbox where aggregate_id = ?::uuid", String.class, orderId))
                .containsExactly("order.cancelled");
    }

    @Test
    @DisplayName("LL-I14 a 200 from the merchant: DELIVERED, signed with Ledgerline-Signature, event id header set")
    void deliversSigned() throws Exception {
        merchantAnswers(200);
        long id = cancelledOrderNotification();
        double deliveredBefore = counter("ledgerline.outbox.delivered");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        OutboxMessage delivered = row(id);
        assertThat(delivered.status()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).isEqualTo(clock.instant());
        assertThat(delivered.attempts()).isEqualTo(1);
        assertThat(delivered.lastError()).isNull();
        assertThat(counter("ledgerline.outbox.delivered") - deliveredBefore).isEqualTo(1);

        List<LoggedRequest> requests = merchant.findAll(postRequestedFor(urlEqualTo(MERCHANT_PATH)));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.getFirst();
        assertThat(request.getHeader(MerchantDeliveryClient.EVENT_ID_HEADER)).isEqualTo(Long.toString(id));
        assertThat(request.getHeader(MerchantDeliveryClient.ATTEMPT_HEADER)).isEqualTo("1");
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");

        // Verify the signature independently of the production signer and verifier.
        byte[] body = request.getBody();
        Map<String, String> signature = parse(request.getHeader(MerchantDeliveryClient.SIGNATURE_HEADER));
        long t = Long.parseLong(signature.get("t"));
        assertThat(t).isEqualTo(clock.instant().getEpochSecond());
        assertThat(signature.get("v1")).isEqualTo(TestSigner.v1(TestSigner.MERCHANT_SECRET, t, body));
        assertThat(signature.get("v1")).isNotEqualTo(TestSigner.v1(TestSigner.CURRENT_SECRET, t, body));

        JsonNode envelope = json.readTree(body);
        assertThat(envelope.get("id").asString()).isEqualTo(Long.toString(id));
        assertThat(envelope.get("type").asString()).isEqualTo("order.cancelled");
        assertThat(envelope.get("data").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(envelope.get("data").get("previousStatus").asString()).isEqualTo("CREATED");
        assertThat(envelope.get("data").get("orderId").asString())
                .isEqualTo(delivered.aggregateId().toString());

        assertThat(dispatcher.dispatchOnce()).as("nothing left to deliver").isZero();
    }

    @Test
    @DisplayName("LL-I15 the merchant always answers 500: attempts climb with growing next_attempt_at, DEAD after 8")
    void retriesThenDead() {
        merchantAnswers(500);
        long id = cancelledOrderNotification();
        OutboxDispatcher seeded = seededDispatcher(7);
        BackoffPolicy expected = seededPolicy(7); // same seed: the same sequence of delays
        double deadBefore = counter("ledgerline.outbox.dead");
        Instant previousNext = row(id).nextAttemptAt();

        for (int attempt = 1; attempt <= 8; attempt++) {
            clock.advance(Duration.between(clock.instant(), row(id).nextAttemptAt()));
            Instant attemptedAt = clock.instant();
            assertThat(seeded.dispatchOnce()).isEqualTo(1);

            OutboxMessage after = row(id);
            assertThat(after.attempts()).isEqualTo(attempt);
            assertThat(after.lastError()).isEqualTo("HTTP 500 from merchant endpoint");
            if (attempt < 8) {
                Duration delay = ((BackoffPolicy.Retry) expected.next(attempt + 1)).delay();
                assertThat(after.status()).isEqualTo(OutboxStatus.PENDING);
                assertThat(after.nextAttemptAt()).isEqualTo(attemptedAt.plus(delay));
                assertThat(delay).isLessThanOrEqualTo(expected.upperBound(attempt + 1));
                assertThat(after.nextAttemptAt()).isAfterOrEqualTo(previousNext);
                previousNext = after.nextAttemptAt();
                if (!delay.isZero()) {
                    assertThat(seeded.dispatchOnce())
                            .as("not due before next_attempt_at")
                            .isZero();
                }
            } else {
                assertThat(after.status()).isEqualTo(OutboxStatus.DEAD);
            }
        }

        assertThat(merchant.findAll(postRequestedFor(urlEqualTo(MERCHANT_PATH))))
                .extracting(r -> r.getHeader(MerchantDeliveryClient.ATTEMPT_HEADER))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(counter("ledgerline.outbox.dead") - deadBefore).isEqualTo(1);
        assertThat(counter("ledgerline.outbox.failed", "reason", "http_5xx")).isGreaterThanOrEqualTo(8);

        clock.advance(Duration.ofDays(1));
        assertThat(seeded.dispatchOnce()).as("DEAD rows are never picked up").isZero();

        asViewer()
                .queryParam("status", "DEAD")
                .get("/api/v1/admin/outbox")
                .then()
                .statusCode(200)
                .body("totalElements", equalTo(1))
                .body("items[0].id", equalTo((int) id))
                .body("items[0].status", equalTo("DEAD"))
                .body("items[0].attempts", equalTo(8))
                .body("items[0].eventType", equalTo("order.cancelled"))
                .body("items[0].payload.status", equalTo("CANCELLED"));
        asViewer()
                .queryParam("status", "PENDING")
                .get("/api/v1/admin/outbox")
                .then()
                .statusCode(200)
                .body("totalElements", equalTo(0));
    }

    @Test
    @DisplayName("LL-I16 replaying a DEAD row after the merchant recovers: DELIVERED, audit names the ADMIN user")
    void replayDead() {
        merchantAnswers(500);
        long id = cancelledOrderNotification();
        driveToDead(id, seededDispatcher(16));
        assertThat(row(id).status()).isEqualTo(OutboxStatus.DEAD);
        merchant.resetAll();
        merchantAnswers(200);

        asAdmin()
                .header(IdempotencyFilter.HEADER, "key-i16-replay")
                .post("/api/v1/admin/outbox/" + id + "/replay")
                .then()
                .statusCode(200)
                .body("status", equalTo("PENDING"))
                .body("attempts", equalTo(0))
                .body("lastError", equalTo(null));

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        OutboxMessage delivered = row(id);
        assertThat(delivered.status()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(delivered.attempts()).isEqualTo(1);
        assertThat(merchant.findAll(postRequestedFor(urlEqualTo(MERCHANT_PATH))))
                .singleElement()
                .satisfies(r -> assertThat(r.getHeader(MerchantDeliveryClient.ATTEMPT_HEADER))
                        .isEqualTo("1"));

        Map<String, Object> audit =
                jdbc.queryForMap("select order_id::text as order_id, actor, detail::text as detail from audit_log"
                        + " where kind = 'OUTBOX_REPLAYED'");
        assertThat(audit.get("actor")).isEqualTo("operator:" + ADMIN_USER);
        assertThat(audit.get("order_id")).isEqualTo(delivered.aggregateId().toString());
        JsonNode detail = json.readTree((String) audit.get("detail"));
        assertThat(detail.get("outboxId").asLong()).isEqualTo(id);
        assertThat(detail.get("previousAttempts").asInt()).isEqualTo(8);
        assertThat(detail.get("lastError").asString()).isEqualTo("HTTP 500 from merchant endpoint");
    }

    @Test
    @DisplayName("LL-I16 replay is refused for a message that is not DEAD (409) or does not exist (404)")
    void replayRefused() {
        merchantAnswers(200);
        long id = cancelledOrderNotification();

        asAdmin()
                .header(IdempotencyFilter.HEADER, "key-i16-not-dead")
                .post("/api/v1/admin/outbox/" + id + "/replay")
                .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:outbox-message-not-replayable"))
                .body("currentStatus", equalTo("PENDING"));
        asAdmin()
                .header(IdempotencyFilter.HEADER, "key-i16-missing")
                .post("/api/v1/admin/outbox/999999/replay")
                .then()
                .statusCode(404)
                .body("type", equalTo("urn:ledgerline:problem:outbox-message-not-found"));
        asViewer()
                .queryParam("status", "LOST")
                .get("/api/v1/admin/outbox")
                .then()
                .statusCode(400)
                .body("type", equalTo("urn:ledgerline:problem:invalid-parameter"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where kind = 'OUTBOX_REPLAYED'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("LL-I17 two dispatchers running concurrently over 200 rows deliver each row exactly once")
    void concurrentDispatchers() throws Exception {
        merchant.stubFor(WireMock.post(urlEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(20)));
        jdbc.update("""
                insert into outbox (aggregate_id, event_type, payload, status, attempts, next_attempt_at, created_at)
                select gen_random_uuid(), 'order.paid', jsonb_build_object('n', n), 'PENDING', 0, ?, ?
                  from generate_series(1, 200) as n
                """, java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        List<OutboxDispatcher> dispatchers = List.of(dispatcher, seededDispatcher(17));
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> claimed;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            claimed = dispatchers.stream()
                    .map(d -> pool.submit(() -> {
                        start.await();
                        int total = 0;
                        for (int n; (n = d.dispatchOnce()) > 0; ) {
                            total += n;
                        }
                        return total;
                    }))
                    .toList();
            start.countDown();
        }

        int first = claimed.get(0).get(1, TimeUnit.SECONDS);
        int second = claimed.get(1).get(1, TimeUnit.SECONDS);
        assertThat(first + second).isEqualTo(200);
        assertThat(first).as("both dispatchers took part").isPositive();
        assertThat(second).as("both dispatchers took part").isPositive();

        List<LoggedRequest> requests = merchant.findAll(postRequestedFor(urlEqualTo(MERCHANT_PATH)));
        assertThat(requests).hasSize(200);
        assertThat(requests.stream()
                        .map(r -> r.getHeader(MerchantDeliveryClient.EVENT_ID_HEADER))
                        .distinct())
                .hasSize(200);
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbox where status = 'DELIVERED' and attempts = 1", Long.class))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("LL-I18 a merchant that takes 10 s: the attempt fails by timeout within about 3 s and is rescheduled")
    void timesOut() {
        merchant.stubFor(WireMock.post(urlEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(10_000)));
        long id = cancelledOrderNotification();
        double timeoutsBefore = counter("ledgerline.outbox.failed", "reason", "timeout");

        long started = System.nanoTime();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(2_900)).isLessThan(Duration.ofSeconds(6));
        OutboxMessage after = row(id);
        assertThat(after.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.lastError()).isEqualTo("no response within 3000 ms");
        assertThat(after.nextAttemptAt()).isAfterOrEqualTo(clock.instant());
        assertThat(after.claimedAt()).isNull();
        assertThat(counter("ledgerline.outbox.failed", "reason", "timeout") - timeoutsBefore)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("LL-I20 the watchdog returns a row stuck IN_FLIGHT to PENDING after 60 s")
    void watchdogReleasesStuckRows() {
        merchantAnswers(200);
        long id = cancelledOrderNotification();
        // A dispatcher claims the row and then "crashes": no result is ever recorded.
        OutboxMessage claimed = store.claimDue(20, clock.instant()).getFirst();
        assertThat(claimed.id()).isEqualTo(id);

        clock.advance(Duration.ofSeconds(60));
        assertThat(watchdog.releaseStuck()).as("exactly 60 s is not yet stuck").isZero();
        assertThat(row(id).status()).isEqualTo(OutboxStatus.IN_FLIGHT);
        assertThat(dispatcher.dispatchOnce())
                .as("IN_FLIGHT rows are not claimed again")
                .isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(watchdog.releaseStuck()).isEqualTo(1);

        OutboxMessage released = row(id);
        assertThat(released.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(released.claimedAt()).isNull();
        assertThat(released.attempts()).as("the lost attempt is not counted").isZero();
        assertThat(released.nextAttemptAt()).isEqualTo(clock.instant());
        assertThat(released.lastError()).isEqualTo("released by watchdog: IN_FLIGHT longer than 60 s");

        assertThat(store.markDelivered(id, claimed.claimedAt(), clock.instant()))
                .as("the crashed dispatcher's late result is ignored")
                .isFalse();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(row(id).status()).isEqualTo(OutboxStatus.DELIVERED);
    }

    @Test
    @DisplayName("an unreachable merchant is a failed attempt with reason connect, rescheduled")
    void unreachableMerchant() {
        long id = cancelledOrderNotification();
        // Port 9 (discard) on loopback: nothing listens there, so the connection is refused.
        MerchantDeliveryClient unreachable = new MerchantDeliveryClient(
                new MerchantProperties(
                        "http://127.0.0.1:9/notifications", List.of(TestSigner.MERCHANT_SECRET), null, null),
                environment,
                clock,
                json);
        OutboxDispatcher dispatcher =
                new OutboxDispatcher(store, unreachable, seededPolicy(9), metrics, observations, clock, properties);
        double before = counter("ledgerline.outbox.failed", "reason", "connect");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        OutboxMessage after = row(id);
        assertThat(after.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.lastError()).startsWith("connection failed: ");
        assertThat(counter("ledgerline.outbox.failed", "reason", "connect") - before)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id}/deliveries shows the order's notifications and their state, publicly")
    void deliveriesForOrder() {
        merchantAnswers(500);
        long id = cancelledOrderNotification();
        String orderId = row(id).aggregateId().toString();
        dispatcher.dispatchOnce();

        given().get("/api/v1/orders/" + orderId + "/deliveries")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].id", equalTo((int) id))
                .body("[0].eventType", equalTo("order.cancelled"))
                .body("[0].status", equalTo("PENDING"))
                .body("[0].attempts", equalTo(1))
                .body("[0].lastError", equalTo("HTTP 500 from merchant endpoint"))
                .body("[0].payload", equalTo(null));
        given().get("/api/v1/orders/0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b/deliveries")
                .then()
                .statusCode(404)
                .body("type", equalTo("urn:ledgerline:problem:order-not-found"));
    }

    @Test
    @DisplayName("outbox gauges report the pending count and the age of the oldest pending row")
    void gauges() {
        merchantAnswers(500);
        assertThat(meters.get("ledgerline.outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("ledgerline.outbox.oldest.pending.age").gauge().value())
                .isZero();

        cancelledOrderNotification();
        cancelledOrderNotification();
        clock.advance(Duration.ofSeconds(30));

        assertThat(meters.get("ledgerline.outbox.pending").gauge().value()).isEqualTo(2);
        assertThat(meters.get("ledgerline.outbox.oldest.pending.age").gauge().value())
                .isEqualTo(30);
        io.micrometer.core.instrument.Timer failed = meters.find("ledgerline.outbox.delivery")
                .tag("outcome", "failed")
                .timer();
        long failedBefore = failed == null ? 0 : failed.count();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(2);
        assertThat(meters.get("ledgerline.outbox.delivery")
                        .tag("outcome", "failed")
                        .timer()
                        .count())
                .isEqualTo(failedBefore + 2);
    }

    private static Map<String, String> parse(String header) {
        Map<String, String> parts = new java.util.HashMap<>();
        for (String element : header.split(",")) {
            int eq = element.indexOf('=');
            assertThat(parts.put(element.substring(0, eq), element.substring(eq + 1)))
                    .as("each element once")
                    .isNull();
        }
        return parts;
    }
}

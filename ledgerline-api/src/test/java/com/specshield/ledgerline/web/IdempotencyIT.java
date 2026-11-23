package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

import com.specshield.ledgerline.application.IdempotencyCleanupJob;
import io.restassured.response.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyIT extends ApiTestSupport {

    @Autowired
    IdempotencyCleanupJob cleanupJob;

    @Test
    @DisplayName("LL-I01 POST without Idempotency-Key returns 400 problem+json naming the missing header")
    void missingKey() {
        postJson("/api/v1/orders", null, ORDER_JSON)
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", endsWith("missing-request-header"))
                .body("detail", containsString("Idempotency-Key"));

        assertThat(count("orders")).isZero();
    }

    @Test
    @DisplayName(
            "LL-I02 same key + same body twice returns identical 201 body and order id, replay header, one order row")
    void sameKeySameBody() {
        Response first = postJson("/api/v1/orders", "key-i02-0001", ORDER_JSON);
        // Same JSON, different key order and whitespace: canonical JSON hashes it the same.
        Response second = postJson(
                "/api/v1/orders",
                "key-i02-0001",
                "{ \"currency\": \"USD\", \"amountMinor\": 2599, \"merchantRef\": \"synthetic-order-1001\" }");

        first.then().statusCode(201);
        second.then().statusCode(201).header(IdempotencyFilter.REPLAYED_HEADER, "true");
        assertThat(first.header(IdempotencyFilter.REPLAYED_HEADER)).isNull();
        assertThat(second.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(second.<String>path("id")).isEqualTo(first.<String>path("id"));
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(second.contentType()).isEqualTo(first.contentType());
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("LL-I03 same key + different body returns 422 idempotency-key-reuse")
    void sameKeyDifferentBody() {
        postJson("/api/v1/orders", "key-i03-0001", ORDER_JSON).then().statusCode(201);

        postJson(
                        "/api/v1/orders",
                        "key-i03-0001",
                        "{\"merchantRef\":\"synthetic-order-1001\",\"amountMinor\":2600,\"currency\":\"USD\"}")
                .then()
                .statusCode(422)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:idempotency-key-reuse"));

        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key on a different endpoint is a different request: 422")
    void sameKeyDifferentPath() {
        String id = createOrder("key-path-0001");

        post("/api/v1/orders/" + id + "/cancel", "key-path-0001")
                .then()
                .statusCode(422)
                .body("type", endsWith("idempotency-key-reuse"));
    }

    @Test
    @DisplayName("LL-I04 10 concurrent POSTs with the same key: one order, each response the same 201 or 409, no 5xx")
    void concurrentSameKey() throws Exception {
        int threads = 10;
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .header(IdempotencyFilter.HEADER, "key-i04-concurrent")
                .POST(HttpRequest.BodyPublishers.ofString(ORDER_JSON))
                .build();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) {
                responses.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).extracting(HttpResponse::statusCode).allMatch(s -> s == 201 || s == 409);
            List<String> created = responses.stream()
                    .filter(r -> r.statusCode() == 201)
                    .map(HttpResponse::body)
                    .distinct()
                    .toList();
            assertThat(created).as("all 201 bodies identical").hasSize(1);
            responses.stream().filter(r -> r.statusCode() == 409).forEach(r -> {
                assertThat(r.body()).contains("idempotency-request-in-progress");
                assertThat(r.headers().firstValue("Retry-After")).contains("1");
            });
        }
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("LL-I05 after 25 hours the same key is accepted as new, and the cleanup job purges the old row")
    void expiredKeyIsNew() {
        String firstId = createOrder("key-i05-0001");

        clock.advance(Duration.ofHours(25));
        assertThat(cleanupJob.run()).isEqualTo(1);
        assertThat(count("idempotency_keys")).isZero();

        Response again = postJson("/api/v1/orders", "key-i05-0001", ORDER_JSON);
        again.then().statusCode(201);
        assertThat(again.header(IdempotencyFilter.REPLAYED_HEADER)).isNull();
        assertThat(again.<String>path("id")).isNotEqualTo(firstId);
        assertThat(count("orders")).isEqualTo(2);
    }

    @Test
    @DisplayName("an expired key is accepted as new even before the cleanup job has run")
    void expiredKeyWithoutCleanup() {
        String firstId = createOrder("key-exp-0001");
        clock.advance(Duration.ofHours(24));

        Response again = postJson("/api/v1/orders", "key-exp-0001", ORDER_JSON);

        again.then().statusCode(201);
        assertThat(again.<String>path("id")).isNotEqualTo(firstId);
        assertThat(count("idempotency_keys")).isEqualTo(1);
    }

    @Test
    @DisplayName("a key just under 24 hours old still replays")
    void notYetExpired() {
        createOrder("key-ttl-0001");
        clock.advance(Duration.ofHours(24).minusSeconds(1));

        postJson("/api/v1/orders", "key-ttl-0001", ORDER_JSON)
                .then()
                .statusCode(201)
                .header(IdempotencyFilter.REPLAYED_HEADER, "true");
        assertThat(cleanupJob.run()).isZero();
    }

    @Test
    @DisplayName("4xx results are stored and replayed byte for byte")
    void clientErrorsAreStored() {
        String invalid = "{\"merchantRef\":\"\",\"amountMinor\":0,\"currency\":\"EUR\"}";
        Response first = postJson("/api/v1/orders", "key-4xx-0001", invalid);
        Response second = postJson("/api/v1/orders", "key-4xx-0001", invalid);

        first.then().statusCode(400).contentType("application/problem+json");
        second.then().statusCode(400).header(IdempotencyFilter.REPLAYED_HEADER, "true");
        assertThat(second.asByteArray()).isEqualTo(first.asByteArray());
        assertThat(jdbc.queryForObject("select state from idempotency_keys", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a 5xx result deletes the IN_PROGRESS row so the client may retry with the same key")
    void serverErrorsReleaseTheKey() {
        post(TestOnlyFailureController.PATH, "key-5xx-0001")
                .then()
                .statusCode(500)
                .contentType("application/problem+json")
                .body("type", endsWith("internal-error"));
        assertThat(count("idempotency_keys")).isZero();

        post(TestOnlyFailureController.PATH, "key-5xx-0001")
                .then()
                .statusCode(200)
                .body("call", equalTo(2));
        assertThat(TestOnlyFailureController.calls()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select response_status from idempotency_keys", Integer.class))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an Idempotency-Key that is too short or has invalid characters is rejected with 400")
    void invalidKeyFormat() {
        for (String key : List.of("short", "has space-123", "x".repeat(129), "bad/chars!!")) {
            postJson("/api/v1/orders", key, ORDER_JSON)
                    .then()
                    .statusCode(400)
                    .contentType("application/problem+json")
                    .body("type", endsWith("invalid-idempotency-key"));
        }
        assertThat(count("orders")).isZero();
        assertThat(count("idempotency_keys")).isZero();
    }

    @Test
    @DisplayName("pay and cancel are idempotent too: the replay returns the stored result")
    void payAndCancelReplay() {
        String id = createOrder("key-cmd-0001");

        Response pay1 = post("/api/v1/orders/" + id + "/pay", "key-pay-0001");
        Response pay2 = post("/api/v1/orders/" + id + "/pay", "key-pay-0001");
        pay1.then().statusCode(202);
        assertThat(pay2.asByteArray()).isEqualTo(pay1.asByteArray());
        assertThat(pay2.header(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");

        Response cancel1 = post("/api/v1/orders/" + id + "/cancel", "key-cancel-0001");
        Response cancel2 = post("/api/v1/orders/" + id + "/cancel", "key-cancel-0001");
        cancel1.then().statusCode(200).body("status", equalTo("CANCELLED"));
        cancel2.then().statusCode(200).header(IdempotencyFilter.REPLAYED_HEADER, "true");
        assertThat(cancel2.asByteArray()).isEqualTo(cancel1.asByteArray());

        // One audit row per executed command, none for replays.
        assertThat(jdbc.queryForList("select kind from audit_log order by id", String.class))
                .containsExactly("ORDER_CREATED", "PAYMENT_REQUESTED", "ORDER_CANCELLED");
    }

    @Test
    @DisplayName("GET requests are never subject to idempotency handling")
    void getIgnoresKeys() {
        String id = createOrder("key-get-0001");

        given().header(IdempotencyFilter.HEADER, "short")
                .get("/api/v1/orders/" + id)
                .then()
                .statusCode(200);
    }
}

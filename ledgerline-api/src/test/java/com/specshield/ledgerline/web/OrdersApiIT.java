package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

import com.specshield.ledgerline.persistence.InboxEventEntity;
import com.specshield.ledgerline.persistence.InboxEventJpaRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrdersApiIT extends ApiTestSupport {

    @Autowired
    InboxEventJpaRepository inbox;

    @Test
    @DisplayName("POST /api/v1/orders returns 201, a Location header and the order")
    void create() {
        var response = postJson("/api/v1/orders", "key-create-0001", ORDER_JSON);

        response.then()
                .statusCode(201)
                .contentType("application/json")
                .body("merchantRef", equalTo("synthetic-order-1001"))
                .body("amountMinor", equalTo(2599))
                .body("currency", equalTo("USD"))
                .body("status", equalTo("CREATED"))
                .body("refundedMinor", equalTo(0))
                .body("version", equalTo(0))
                .body("createdAt", equalTo("2026-01-15T10:00:00Z"))
                .body("lastProviderEventAt", nullValue());
        String id = response.path("id");
        assertThat(UUID.fromString(id).version()).isEqualTo(7);
        assertThat(response.header("Location")).isEqualTo("/api/v1/orders/" + id);
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} returns the order; an unknown id is 404 order-not-found")
    void get() {
        String id = createOrder("key-get-0002");

        given().get("/api/v1/orders/" + id).then().statusCode(200).body("id", equalTo(id));
        given().get("/api/v1/orders/" + UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("type", endsWith("order-not-found"));
    }

    @Test
    @DisplayName("GET /api/v1/orders lists newest first, filters by status and pages")
    void list() {
        String first = createOrder("key-list-0001");
        clock.advance(Duration.ofSeconds(1));
        String second = createOrder("key-list-0002");
        clock.advance(Duration.ofSeconds(1));
        String third = createOrder("key-list-0003");
        post("/api/v1/orders/" + second + "/cancel", "key-list-cancel").then().statusCode(200);

        given().get("/api/v1/orders")
                .then()
                .statusCode(200)
                .body("items.id", equalTo(List.of(third, second, first)))
                .body("totalElements", equalTo(3))
                .body("page", equalTo(0))
                .body("size", equalTo(20));

        given().queryParam("status", "CANCELLED")
                .get("/api/v1/orders")
                .then()
                .body("items.id", equalTo(List.of(second)))
                .body("totalElements", equalTo(1));

        given().queryParam("page", 1)
                .queryParam("size", 2)
                .get("/api/v1/orders")
                .then()
                .body("items.id", equalTo(List.of(first)))
                .body("totalPages", equalTo(2));
    }

    @Test
    @DisplayName("size above 100, negative page and an unknown status are 400 problems")
    void listValidation() {
        given().queryParam("size", 101)
                .get("/api/v1/orders")
                .then()
                .statusCode(400)
                .body("type", endsWith("validation-error"));
        given().queryParam("page", -1).get("/api/v1/orders").then().statusCode(400);
        given().queryParam("status", "NOPE")
                .get("/api/v1/orders")
                .then()
                .statusCode(400)
                .body("type", endsWith("invalid-parameter"));
        given().queryParam("size", 100).get("/api/v1/orders").then().statusCode(200);
    }

    @Test
    @DisplayName("POST /pay returns 202 with the MockPay stub reference and leaves the status unchanged")
    void pay() {
        String id = createOrder("key-pay-0002");

        post("/api/v1/orders/" + id + "/pay", "key-pay-0003")
                .then()
                .statusCode(202)
                .body("orderId", equalTo(id))
                .body("status", equalTo("CREATED"))
                .body("paymentReference", equalTo("mp_stub_" + id));
        given().get("/api/v1/orders/" + id).then().body("status", equalTo("CREATED"));
    }

    @Test
    @DisplayName("pay and cancel need an Idempotency-Key")
    void commandsNeedKey() {
        String id = createOrder("key-cmd-0002");

        post("/api/v1/orders/" + id + "/pay", null)
                .then()
                .statusCode(400)
                .body("type", endsWith("missing-request-header"));
        post("/api/v1/orders/" + id + "/cancel", null).then().statusCode(400);
    }

    @Test
    @DisplayName("cancel moves CREATED to CANCELLED; cancelling again or paying a cancelled order is 409")
    void cancel() {
        String id = createOrder("key-cancel-0002");

        post("/api/v1/orders/" + id + "/cancel", "key-cancel-0003")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("version", equalTo(1));
        post("/api/v1/orders/" + id + "/cancel", "key-cancel-0004")
                .then()
                .statusCode(409)
                .body("type", endsWith("illegal-transition"))
                .body("currentStatus", equalTo("CANCELLED"));
        post("/api/v1/orders/" + id + "/pay", "key-pay-0004").then().statusCode(409);
        post("/api/v1/orders/" + UUID.randomUUID() + "/cancel", "key-cancel-0005")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("GET /timeline merges audit rows and inbox entries by time")
    void timeline() {
        String id = createOrder("key-tl-0001"); // audit at T0
        inbox.saveAndFlush(new InboxEventEntity(
                "mockpay",
                "evt-tl-1",
                UUID.fromString(id),
                "{\"type\":\"PAYMENT_INITIATED\",\"sequence\":1}",
                true,
                clock.instant().plusSeconds(30)));
        inbox.saveAndFlush(new InboxEventEntity(
                "mockpay",
                "evt-other",
                UUID.randomUUID(),
                "{}",
                true,
                clock.instant().plusSeconds(31)));
        clock.advance(Duration.ofMinutes(1));
        post("/api/v1/orders/" + id + "/pay", "key-tl-pay").then().statusCode(202); // audit at T0 + 60s

        given().header(CorrelationIdFilter.HEADER, "7b0f9c3e-1d2a-4b8c-9e6f-0a1b2c3d4e5f")
                .get("/api/v1/orders/" + id + "/timeline")
                .then()
                .statusCode(200)
                .body("$", hasSize(3))
                .body("source", equalTo(List.of("AUDIT", "INBOX", "AUDIT")))
                .body("kind", equalTo(List.of("ORDER_CREATED", "PROVIDER_EVENT", "PAYMENT_REQUESTED")))
                .body("[0].detail.amountMinor", equalTo(2599))
                .body("[0].actor", equalTo("api"))
                .body("[0].correlationId", matchesPattern("[0-9a-f-]{36}"))
                .body("[1].detail.type", equalTo("PAYMENT_INITIATED"))
                .body("[1].providerEventId", equalTo("evt-tl-1"))
                .body("[1].actor", equalTo("mockpay"))
                .body("[2].detail.paymentReference", equalTo("mp_stub_" + id))
                .body("at", equalTo(List.of("2026-01-15T10:00:00Z", "2026-01-15T10:00:30Z", "2026-01-15T10:01:00Z")));

        given().get("/api/v1/orders/" + UUID.randomUUID() + "/timeline").then().statusCode(404);
    }
}

package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.specshield.ledgerline.PostgresContainerConfig;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real MockPay, real signed HTTP webhooks to our own endpoint, real clock. Separate context: in the shared API test
 * context MockPay is disabled so other tests' payments do not trigger webhooks.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerline.mockpay.enabled=true")
@ActiveProfiles("test")
@Import(PostgresContainerConfig.class)
class MockPayEndToEndIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName(
            "LL-E2E-mockpay create, pay, MockPay webhooks make it PAID within 5 s; timeline has INITIATED and SUCCEEDED")
    void fullFlow() {
        String id = given().contentType(ContentType.JSON)
                .header("Idempotency-Key", "key-e2e-0001")
                .body("{\"merchantRef\":\"synthetic-e2e-1\",\"amountMinor\":4200,\"currency\":\"USD\"}")
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String reference = given().header("Idempotency-Key", "key-e2e-pay-0001")
                .post("/api/v1/orders/" + id + "/pay")
                .then()
                .statusCode(202)
                .extract()
                .path("paymentReference");
        assertThat(reference).startsWith("mp_");

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(
                        () -> assertThat(given().get("/api/v1/orders/" + id).<String>path("status"))
                                .isEqualTo("PAID"));

        List<String> providerEvents = given().get("/api/v1/orders/" + id + "/timeline")
                .then()
                .statusCode(200)
                .extract()
                .path("findAll { it.source == 'INBOX' }.detail.type");
        assertThat(providerEvents).containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCEEDED");
        assertThat(jdbc.queryForObject(
                        "select status from mockpay_ledger where payment_id = ?", String.class, reference))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList(
                        "select outcome from order_events_inbox where order_id = ?::uuid order by id",
                        String.class,
                        id))
                .containsExactly("APPLIED", "APPLIED");
    }
}

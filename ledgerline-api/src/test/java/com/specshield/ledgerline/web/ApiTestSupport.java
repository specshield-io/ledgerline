package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;

import com.specshield.ledgerline.MutableClock;
import com.specshield.ledgerline.MutableClockConfig;
import com.specshield.ledgerline.PostgresContainerConfig;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Shared context for API integration tests: real PostgreSQL 17, mutable clock, clean tables per test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, MutableClockConfig.class})
abstract class ApiTestSupport {

    static final String ORDER_JSON = """
            {"merchantRef":"synthetic-order-1001","amountMinor":2599,"currency":"USD"}""";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    FailureInjectionHook failureHook;

    @BeforeEach
    void resetState() {
        RestAssured.port = port;
        clock.reset();
        TestOnlyFailureController.reset();
        failureHook.disarm();
        jdbc.execute(
                "TRUNCATE orders, order_events_inbox, audit_log, idempotency_keys, outbox, mockpay_ledger RESTART IDENTITY");
    }

    static Response postJson(String path, String key, String body) {
        var spec = given().contentType(ContentType.JSON).body(body);
        if (key != null) {
            spec = spec.header(IdempotencyFilter.HEADER, key);
        }
        return spec.when().post(path);
    }

    static Response post(String path, String key) {
        var spec = given();
        if (key != null) {
            spec = spec.header(IdempotencyFilter.HEADER, key);
        }
        return spec.when().post(path);
    }

    static String createOrder(String key) {
        return postJson("/api/v1/orders", key, ORDER_JSON)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}

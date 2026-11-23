package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Roles, public paths, security headers and CORS. Synthetic accounts from the test profile. */
class SecurityIT extends ApiTestSupport {

    long deadOutboxRow() {
        jdbc.update("""
                insert into outbox (aggregate_id, event_type, payload, status, attempts, next_attempt_at, created_at,
                                    last_error)
                values (gen_random_uuid(), 'order.paid', '{}', 'DEAD', 8, now(), now(), 'HTTP 500')
                """);
        return jdbc.queryForObject("select max(id) from outbox", Long.class);
    }

    @Test
    @DisplayName("LL-C04 admin list: anonymous 401 with WWW-Authenticate, wrong password 401, VIEWER and ADMIN 200")
    void adminList() {
        Response anonymous = given().get("/api/v1/admin/outbox");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.header("WWW-Authenticate")).startsWith("Basic realm=\"ledgerline\"");
        assertThat(anonymous.<String>path("type")).isEqualTo("urn:ledgerline:problem:unauthorized");

        given().auth()
                .preemptive()
                .basic(ADMIN_USER, "not-the-password")
                .get("/api/v1/admin/outbox")
                .then()
                .statusCode(401);
        asViewer().get("/api/v1/admin/outbox").then().statusCode(200);
        asAdmin().get("/api/v1/admin/outbox").then().statusCode(200);
    }

    @Test
    @DisplayName("LL-C04 replay: anonymous 401, VIEWER 403, ADMIN 200")
    void replayRoles() {
        long id = deadOutboxRow();
        String path = "/api/v1/admin/outbox/" + id + "/replay";

        given().header(IdempotencyFilter.HEADER, "key-c04-anon")
                .post(path)
                .then()
                .statusCode(401);
        asViewer()
                .header(IdempotencyFilter.HEADER, "key-c04-viewer")
                .post(path)
                .then()
                .statusCode(403)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:forbidden"));
        assertThat(jdbc.queryForObject("select status from outbox where id = ?", String.class, id))
                .isEqualTo("DEAD");

        asAdmin()
                .header(IdempotencyFilter.HEADER, "key-c04-admin")
                .post(path)
                .then()
                .statusCode(200)
                .body("status", equalTo("PENDING"));
    }

    @Test
    @DisplayName("LL-C04 demo reset: anonymous 401, VIEWER 403, ADMIN 200")
    void resetRoles() {
        given().header(IdempotencyFilter.HEADER, "key-c04-reset-a")
                .post("/api/v1/admin/reset")
                .then()
                .statusCode(401);
        asViewer()
                .header(IdempotencyFilter.HEADER, "key-c04-reset-v")
                .post("/api/v1/admin/reset")
                .then()
                .statusCode(403);
        assertThat(count("orders")).isZero();

        asAdmin()
                .header(IdempotencyFilter.HEADER, "key-c04-reset-x")
                .post("/api/v1/admin/reset")
                .then()
                .statusCode(200)
                .body("reset", equalTo(true))
                .body("seededOrders", equalTo(25));
    }

    @Test
    @DisplayName("Prometheus metrics need VIEWER or ADMIN")
    void prometheus() {
        given().get("/actuator/prometheus").then().statusCode(401);
        asViewer().get("/actuator/prometheus").then().statusCode(200);
    }

    @Test
    @DisplayName("public: orders, timeline, meta, chaos list, health and the OpenAPI document need no credentials")
    void publicPaths() {
        String id = createOrder("key-sec-public-1");
        given().get("/api/v1/orders").then().statusCode(200);
        given().get("/api/v1/orders/" + id).then().statusCode(200);
        given().get("/api/v1/orders/" + id + "/timeline").then().statusCode(200);
        given().get("/api/v1/chaos/scenarios").then().statusCode(200).body("size()", equalTo(8));
        given().get("/actuator/health").then().statusCode(200);
        given().get("/v3/api-docs").then().statusCode(200);
        given().get("/api/v1/meta")
                .then()
                .statusCode(200)
                .body("demo", equalTo(true))
                .body("dataResetsAt", equalTo("03:00 UTC"))
                .body("version", not(equalTo("unknown")))
                .body("gitSha", equalTo("unknown"));
    }

    @Test
    @DisplayName("security headers: HSTS, nosniff, frame-ancestors none, X-Frame-Options, Referrer-Policy")
    void headers() {
        for (Response response : new Response[] {
            given().get("/api/v1/meta"), given().get("/api/v1/orders/not-a-uuid"), given().get("/actuator/env")
        }) {
            assertThat(response.header("Strict-Transport-Security")).isEqualTo("max-age=31536000 ; includeSubDomains");
            assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.header("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
            assertThat(response.header("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        }
    }

    @Test
    @DisplayName("CORS: the configured origins may call the API from a browser; others are refused")
    void cors() {
        given().header("Origin", "https://www.specshield.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,idempotency-key")
                .options("/api/v1/chaos/scenarios/happy-path")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "https://www.specshield.example")
                .header("Access-Control-Allow-Headers", startsWith("content-type"));
        given().header("Origin", "http://localhost:5173")
                .get("/api/v1/meta")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", "http://localhost:5173");
        given().header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .options("/api/v1/orders")
                .then()
                .statusCode(403);
        Response foreign = given().header("Origin", "https://evil.example").get("/api/v1/meta");
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.header("Access-Control-Allow-Origin")).isNull();
    }
}

package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.response.Response;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Rate limiting on, with the test client's loopback address as a trusted proxy so X-Forwarded-For picks the IP. */
@TestPropertySource(
        properties = {
            "ledgerline.rate-limit.enabled=true",
            "ledgerline.rate-limit.trusted-proxies[0]=127.0.0.1/32",
            "ledgerline.rate-limit.trusted-proxies[1]=::1/128"
        })
class RateLimitIT extends ApiTestSupport {

    static Response listOrdersFrom(String ip) {
        return given().header("X-Forwarded-For", ip).get("/api/v1/orders");
    }

    @Test
    @DisplayName(
            "LL-C03 the 241st public request in a minute from one IP is 429 with Retry-After; another IP is unaffected")
    void publicLimit() {
        for (int i = 1; i <= 240; i++) {
            assertThat(listOrdersFrom("203.0.113.7").statusCode())
                    .as("request %d", i)
                    .isEqualTo(200);
        }

        Response limited = listOrdersFrom("203.0.113.7");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isEqualTo("60");
        assertThat(limited.contentType()).startsWith("application/problem+json");
        assertThat(limited.<String>path("type")).isEqualTo("urn:ledgerline:problem:rate-limited");
        assertThat(limited.<String>path("instance")).isEqualTo("/api/v1/orders");
        assertThat(limited.<String>path("correlationId")).isEqualTo(limited.header(CorrelationIdFilter.HEADER));
        assertThat(limited.header("X-Content-Type-Options"))
                .as("security headers on 429 too")
                .isEqualTo("nosniff");

        assertThat(listOrdersFrom("203.0.113.8").statusCode())
                .as("a different IP")
                .isEqualTo(200);
        assertThat(given().header("X-Forwarded-For", "203.0.113.7")
                        .get("/api/v1/meta")
                        .statusCode())
                .as("the public budget covers every public endpoint")
                .isEqualTo(429);

        clock.advance(Duration.ofSeconds(59));
        assertThat(listOrdersFrom("203.0.113.7").statusCode())
                .as("still inside the minute")
                .isEqualTo(429);
        clock.advance(Duration.ofSeconds(1));
        assertThat(listOrdersFrom("203.0.113.7").statusCode())
                .as("the next minute")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("LL-C03 chaos starts are limited to 10 per minute per IP, separately from the public budget")
    void chaosLimit() {
        for (int i = 1; i <= 10; i++) {
            given().header("X-Forwarded-For", "198.51.100.20")
                    .header(IdempotencyFilter.HEADER, "key-c03-chaos-" + i)
                    .post("/api/v1/chaos/scenarios/happy-path")
                    .then()
                    .statusCode(503); // MockPay is off in this context: refused, but still counted
        }

        given().header("X-Forwarded-For", "198.51.100.20")
                .header(IdempotencyFilter.HEADER, "key-c03-chaos-11")
                .post("/api/v1/chaos/scenarios/happy-path")
                .then()
                .statusCode(429)
                .header("Retry-After", "60")
                .body("type", equalTo("urn:ledgerline:problem:rate-limited"));
        assertThat(listOrdersFrom("198.51.100.20").statusCode())
                .as("public budget untouched")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("LL-C03 X-Forwarded-For entries left of the trusted hop cannot pick someone else's bucket")
    void spoofedHeader() {
        for (int i = 0; i < 240; i++) {
            listOrdersFrom("203.0.113.30");
        }
        // The client claims to be someone else, but the trusted proxy appends the real address last.
        assertThat(listOrdersFrom("192.0.2.99, 203.0.113.30").statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("webhooks are authenticated by signature and not rate limited")
    void webhooksNotLimited() {
        for (int i = 0; i < 70; i++) {
            given().header("X-Forwarded-For", "203.0.113.40")
                    .contentType("application/json")
                    .body("{}")
                    .post("/webhooks/mockpay")
                    .then()
                    .statusCode(400);
        }
    }
}

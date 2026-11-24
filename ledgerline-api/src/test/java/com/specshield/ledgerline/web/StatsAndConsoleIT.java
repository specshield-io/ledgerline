package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.containsString;

import com.specshield.ledgerline.webhook.MockPayWebhookController;
import com.specshield.ledgerline.webhook.TestSigner;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** GET /api/v1/stats for the console's metrics strip, and the console's static files. */
class StatsAndConsoleIT extends ApiTestSupport {

    Response deliver(String body, String secret) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return given().contentType("application/json")
                .header(
                        MockPayWebhookController.SIGNATURE_HEADER,
                        TestSigner.header(secret, clock.instant().getEpochSecond(), bytes))
                .body(bytes)
                .post(MockPayWebhookController.PATH);
    }

    static String event(String id, String type, long sequence, String orderId) {
        return """
                {"id":"%s","type":"%s","created":1,"sequence":%d,"data":{"orderId":"%s","amountMinor":2599,"currency":"USD"}}""".formatted(id, type, sequence, orderId);
    }

    long stat(String name) {
        return given().get("/api/v1/stats")
                .then()
                .statusCode(200)
                .extract()
                .<Number>path(name)
                .longValue();
    }

    @Test
    @DisplayName("stats count received, applied, duplicate and signature-rejected webhooks, and pending notifications")
    void stats() {
        long received = stat("received");
        long applied = stat("applied");
        long duplicates = stat("duplicatesBlocked");
        long rejected = stat("signaturesRejected");
        String orderId = createOrder("key-stats-0001");

        deliver(event("evt_stats_1", "PAYMENT_INITIATED", 1, orderId), TestSigner.CURRENT_SECRET);
        deliver(event("evt_stats_2", "PAYMENT_SUCCEEDED", 2, orderId), TestSigner.CURRENT_SECRET);
        deliver(event("evt_stats_2", "PAYMENT_SUCCEEDED", 2, orderId), TestSigner.CURRENT_SECRET);
        deliver(event("evt_stats_3", "PAYMENT_FAILED", 3, orderId), "not-the-secret-not-the-secret-0000");
        // Validly signed but not an event: malformed, not a signature failure.
        deliver("{\"hello\":true}", TestSigner.CURRENT_SECRET);

        assertThat(stat("received") - received).isEqualTo(5);
        assertThat(stat("applied") - applied).isEqualTo(2);
        assertThat(stat("duplicatesBlocked") - duplicates).isEqualTo(1);
        assertThat(stat("signaturesRejected") - rejected).isEqualTo(1);
        assertThat(stat("outboxPending")).as("order.paid waits for the relay").isEqualTo(1);
        assertThat(stat("outboxDead")).isZero();
        given().get("/api/v1/stats").then().body("countingSince", containsString("T"));
    }

    @Test
    @DisplayName("the operations console is served at / and /outbox with the security headers, without credentials")
    void console() {
        assumeThat(new ClassPathResource("static/index.html").exists())
                .as("built with the console (not -Dskip.frontend)")
                .isTrue();

        for (String path : new String[] {"/", "/outbox"}) {
            Response page = given().get(path);
            assertThat(page.statusCode()).as(path).isEqualTo(200);
            assertThat(page.contentType()).startsWith("text/html");
            assertThat(page.asString()).contains("<div id=\"root\"></div>");
            assertThat(page.header("Content-Security-Policy"))
                    .contains("frame-ancestors 'none'")
                    .contains("script-src 'self'");
            assertThat(page.header("X-Content-Type-Options")).isEqualTo("nosniff");
        }
        String script = given().get("/").asString().replaceAll("(?s).*src=\"(/assets/[^\"]+\\.js)\".*", "$1");
        given().get(script).then().statusCode(200).contentType(containsString("javascript"));
        given().get("/favicon.svg").then().statusCode(200);
        given().get("/assets/does-not-exist.js").then().statusCode(404);
    }
}

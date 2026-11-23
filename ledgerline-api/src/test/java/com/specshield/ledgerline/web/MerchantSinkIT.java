package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.specshield.ledgerline.application.OutboxStatus;
import com.specshield.ledgerline.outbox.MerchantDeliveryClient;
import com.specshield.ledgerline.outbox.OutboxDispatcher;
import com.specshield.ledgerline.webhook.MerchantSink;
import com.specshield.ledgerline.webhook.MerchantSinkController;
import com.specshield.ledgerline.webhook.TestSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The relay delivering to the built-in demo merchant over real HTTP (the test profile's default merchant URL). */
class MerchantSinkIT extends ApiTestSupport {

    @Autowired
    OutboxDispatcher dispatcher;

    String status(long id) {
        return jdbc.queryForObject("select status from outbox where id = ?", String.class, id);
    }

    long cancelledOrderNotification(String suffix) {
        String orderId = createOrder("key-sink-order-" + suffix);
        post("/api/v1/orders/" + orderId + "/cancel", "key-sink-cancel-" + suffix)
                .then()
                .statusCode(200);
        return jdbc.queryForObject("select id from outbox where aggregate_id = ?::uuid", Long.class, orderId);
    }

    @Test
    @DisplayName("the demo merchant verifies the signature, records the delivery and acknowledges it")
    void endToEnd() {
        long id = cancelledOrderNotification("a");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(status(id)).isEqualTo(OutboxStatus.DELIVERED.name());
        assertThat(merchantSink.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.eventId()).isEqualTo(Long.toString(id));
            assertThat(d.attempt()).isEqualTo("1");
            assertThat(d.eventType()).isEqualTo("order.cancelled");
            assertThat(d.signatureValid()).isTrue();
            assertThat(d.duplicate()).isFalse();
            assertThat(d.responseStatus()).isEqualTo(200);
        });
        given().get(MerchantSinkController.PATH)
                .then()
                .statusCode(200)
                .body("mode", equalTo("OK"))
                .body("deliveries[0].eventId", equalTo(Long.toString(id)));
    }

    @Test
    @DisplayName("in FAIL mode the demo merchant answers 500 and the relay schedules a retry")
    void failMode() {
        long id = cancelledOrderNotification("b");
        merchantSink.setMode(MerchantSink.Mode.FAIL);

        dispatcher.dispatchOnce();

        assertThat(status(id)).isEqualTo(OutboxStatus.PENDING.name());
        assertThat(jdbc.queryForObject("select last_error from outbox where id = ?", String.class, id))
                .isEqualTo("HTTP 500 from merchant endpoint");
        assertThat(merchantSink.deliveries())
                .singleElement()
                .satisfies(d -> assertThat(d.responseStatus()).isEqualTo(500));
    }

    @Test
    @DisplayName("in SLOW mode the demo merchant answers after its delay")
    void slowMode() {
        long id = cancelledOrderNotification("c");
        merchantSink.setMode(MerchantSink.Mode.SLOW);
        merchantSink.setSlowDelay(Duration.ofMillis(200));

        dispatcher.dispatchOnce();

        assertThat(status(id)).isEqualTo(OutboxStatus.DELIVERED.name());
        assertThat(merchantSink.slowDelay()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("the demo merchant rejects a forged signature and flags a redelivered event id as a duplicate")
    void forgedAndDuplicate() {
        byte[] body = "{\"id\":\"1\",\"type\":\"order.paid\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        long t = clock.instant().getEpochSecond();

        given().contentType("application/json")
                .header(MerchantDeliveryClient.SIGNATURE_HEADER, TestSigner.header(TestSigner.CURRENT_SECRET, t, body))
                .header(MerchantDeliveryClient.EVENT_ID_HEADER, "1")
                .body(body)
                .post(MerchantSinkController.PATH)
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:ledgerline:problem:invalid-signature"));

        for (String expectedDuplicate : new String[] {null, "true"}) {
            var response = given().contentType("application/json")
                    .header(
                            MerchantDeliveryClient.SIGNATURE_HEADER,
                            TestSigner.header(TestSigner.MERCHANT_SECRET, t, body))
                    .header(MerchantDeliveryClient.EVENT_ID_HEADER, "1")
                    .body(body)
                    .post(MerchantSinkController.PATH)
                    .then()
                    .statusCode(200)
                    .body("received", equalTo(true));
            if (expectedDuplicate == null) {
                response.body("duplicate", equalTo(null));
            } else {
                response.body("duplicate", equalTo(true));
            }
        }
        assertThat(merchantSink.deliveries())
                .extracting(MerchantSink.Delivery::signatureValid, MerchantSink.Delivery::duplicate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(true, true),
                        org.assertj.core.groups.Tuple.tuple(true, false),
                        org.assertj.core.groups.Tuple.tuple(false, false));
    }
}

package com.specshield.ledgerline.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.specshield.ledgerline.outbox.OutboxDispatcher;
import com.specshield.ledgerline.webhook.MockPayWebhookController;
import com.specshield.ledgerline.webhook.TestSigner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Tracing on (as in a deployment): the custom observations run and log lines carry a trace id. */
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends ApiTestSupport {

    private static final Pattern TRACED_WEBHOOK_LINE =
            Pattern.compile("\\[[0-9a-f-]{36}] \\[[0-9a-f]{32}] .*mockpay webhook evt_obs_1 ");

    @Autowired
    MeterRegistry meters;

    @Autowired
    OutboxDispatcher dispatcher;

    long timer(String name) {
        Timer timer = meters.find(name).timer();
        return timer == null ? 0 : timer.count();
    }

    void deliver(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        given().contentType("application/json")
                .header(
                        MockPayWebhookController.SIGNATURE_HEADER,
                        TestSigner.header(
                                TestSigner.CURRENT_SECRET, clock.instant().getEpochSecond(), bytes))
                .body(bytes)
                .post(MockPayWebhookController.PATH)
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("webhook.verify, webhook.process and outbox.deliver observations run; log lines carry a traceId")
    void customObservations(CapturedOutput output) {
        long verify = timer("webhook.verify");
        long process = timer("webhook.process");
        long outbox = timer("outbox.deliver");
        String orderId = createOrder("key-obs-0001");
        post("/api/v1/orders/" + orderId + "/cancel", "key-obs-0002").then().statusCode(200);

        deliver("""
                {"id":"evt_obs_1","type":"PAYMENT_INITIATED","created":1,"sequence":1,"data":{"orderId":"%s","amountMinor":2599,"currency":"USD"}}""".formatted(orderId));
        dispatcher.dispatchOnce();

        assertThat(timer("webhook.verify") - verify).isEqualTo(1);
        assertThat(timer("webhook.process") - process).isEqualTo(1);
        assertThat(timer("outbox.deliver") - outbox).isEqualTo(1);
        assertThat(output.getOut()
                        .lines()
                        .anyMatch(l -> TRACED_WEBHOOK_LINE.matcher(l).find()))
                .as("the webhook's log line has [correlationId] [traceId]")
                .isTrue();
    }
}

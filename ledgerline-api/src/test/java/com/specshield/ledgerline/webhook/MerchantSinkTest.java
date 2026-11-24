package com.specshield.ledgerline.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.specshield.ledgerline.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** The demo merchant: modes, time windows, deduplication, and its controller. */
class MerchantSinkTest {

    final MutableClock clock = new MutableClock();
    final MerchantSink sink =
            new MerchantSink(new MerchantSinkProperties(true, List.of(TestSigner.MERCHANT_SECRET), null, 3), clock);
    final MerchantSinkController controller =
            new MerchantSinkController(sink, JsonMapper.builder().build());
    final MockHttpServletRequest request = new MockHttpServletRequest("POST", MerchantSinkController.PATH);

    ResponseEntity<?> post(String eventId, String body, String secret) throws InterruptedException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = secret == null
                ? null
                : TestSigner.header(secret, clock.instant().getEpochSecond(), bytes);
        return controller.receive(signature, eventId, "1", bytes, request);
    }

    @Test
    @DisplayName("FAIL and SLOW last for their window, measured with the clock, then the sink is OK again")
    void windows() {
        assertThat(sink.mode()).isEqualTo(MerchantSink.Mode.OK);
        sink.failFor(Duration.ofMinutes(2));
        assertThat(sink.mode()).isEqualTo(MerchantSink.Mode.FAIL);
        assertThat(sink.modeUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));
        clock.advance(Duration.ofSeconds(119));
        assertThat(sink.mode()).isEqualTo(MerchantSink.Mode.FAIL);
        clock.advance(Duration.ofSeconds(1));
        assertThat(sink.mode()).isEqualTo(MerchantSink.Mode.OK);

        sink.slowFor(Duration.ofMillis(5), Duration.ofSeconds(10));
        assertThat(sink.mode()).isEqualTo(MerchantSink.Mode.SLOW);
        assertThat(sink.slowDelay()).isEqualTo(Duration.ofMillis(5));
        sink.setMode(MerchantSink.Mode.FAIL);
        assertThat(sink.modeUntil()).as("setMode has no window").isNull();
        clock.advance(Duration.ofHours(1));
        assertThat(sink.mode()).isEqualTo(MerchantSink.Mode.FAIL);

        sink.reset();
        assertThat(sink.mode()).isEqualTo(MerchantSink.Mode.OK);
        assertThat(sink.slowDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(sink.modeUntil()).isNull();
    }

    @Test
    @DisplayName("valid deliveries are acknowledged, repeats flagged as duplicates, the log keeps the newest few")
    void deliveries() throws InterruptedException {
        String body = "{\"id\":\"1\",\"type\":\"order.paid\",\"data\":{}}";

        assertThat(post("1", body, TestSigner.MERCHANT_SECRET).getStatusCode().value())
                .isEqualTo(200);
        ResponseEntity<?> again = post("1", body, TestSigner.MERCHANT_SECRET);
        assertThat(again.getBody()).asString().contains("duplicate=true");
        post("2", "{\"type\":7}", TestSigner.MERCHANT_SECRET);
        post("3", "not json", TestSigner.MERCHANT_SECRET);

        List<MerchantSink.Delivery> log = sink.deliveries();
        assertThat(log).hasSize(3); // capacity 3: the oldest was dropped
        assertThat(log.getFirst().eventId()).isEqualTo("3");
        assertThat(log.getFirst().eventType()).isNull();
        assertThat(log.get(1).eventType()).as("type that is not a string").isNull();
        assertThat(log.get(2).duplicate()).isTrue();
        assertThat(log.get(2).eventType()).isEqualTo("order.paid");
        assertThat(controller.deliveries()).containsEntry("mode", MerchantSink.Mode.OK);
    }

    @Test
    @DisplayName("bad or missing signatures and oversized bodies are 400 and recorded as invalid, not accepted")
    void rejects() throws InterruptedException {
        assertThat(post("9", "{}", TestSigner.CURRENT_SECRET).getStatusCode().value())
                .isEqualTo(400);
        assertThat(post("9", "{}", null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller
                        .receive(null, "9", "1", new byte[64 * 1024 + 1], request)
                        .getStatusCode()
                        .value())
                .isEqualTo(400);

        assertThat(sink.deliveries()).hasSize(3).allSatisfy(d -> {
            assertThat(d.signatureValid()).isFalse();
            assertThat(d.responseStatus()).isEqualTo(400);
        });
        assertThat(post("9", "{}", TestSigner.MERCHANT_SECRET).getBody())
                .asString()
                .doesNotContain("duplicate");
    }

    @Test
    @DisplayName("FAIL answers 500 (not accepted, so a retry is not a duplicate); SLOW answers after the delay")
    void modes() throws InterruptedException {
        sink.setMode(MerchantSink.Mode.FAIL);
        assertThat(post("5", "{}", TestSigner.MERCHANT_SECRET).getStatusCode().value())
                .isEqualTo(500);
        sink.setMode(MerchantSink.Mode.SLOW);
        sink.setSlowDelay(Duration.ofMillis(20));
        long started = System.nanoTime();
        ResponseEntity<?> slow = post("5", "{}", TestSigner.MERCHANT_SECRET);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(20));
        assertThat(slow.getStatusCode().value()).isEqualTo(200);
        assertThat(slow.getBody()).asString().doesNotContain("duplicate");
    }

    @Test
    @DisplayName("the sink needs a secret, and its properties never print secrets")
    void configuration() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new MerchantSink(new MerchantSinkProperties(true, null, null, 0), clock));
        MerchantSinkProperties defaults = new MerchantSinkProperties(true, List.of("x".repeat(40)), null, 0);
        assertThat(defaults.tolerance()).isEqualTo(Duration.ofSeconds(300));
        assertThat(defaults.capacity()).isEqualTo(500);
        assertThat(defaults.toString()).doesNotContain("x".repeat(40)).contains("1 redacted");
        WebhookProperties webhook = new WebhookProperties(List.of("y".repeat(40)), null, null);
        assertThat(webhook.tolerance()).isEqualTo(Duration.ofSeconds(300));
        assertThat(webhook.orphanRetryWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(webhook.toString()).doesNotContain("y".repeat(40)).contains("1 redacted");
    }
}

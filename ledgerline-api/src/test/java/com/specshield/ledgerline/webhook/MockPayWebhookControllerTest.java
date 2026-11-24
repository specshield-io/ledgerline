package com.specshield.ledgerline.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.specshield.ledgerline.application.PaymentEventService;
import com.specshield.ledgerline.application.PaymentEventService.Outcome;
import com.specshield.ledgerline.application.PaymentEventService.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** The receiver's decisions, with the real verifier and decoder and a mocked event service. */
class MockPayWebhookControllerTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    final PaymentEventService events = mock(PaymentEventService.class);
    final MeterRegistry meters = new SimpleMeterRegistry();
    final TestObservationRegistry observations = TestObservationRegistry.create();
    final MockPayWebhookController controller = new MockPayWebhookController(
            new WebhookSignatureVerifier(List.of(TestSigner.CURRENT_SECRET), Duration.ofSeconds(300)),
            new MockPayEventDecoder(JsonMapper.builder().build()),
            events,
            new WebhookMetrics(meters),
            observations,
            Clock.fixed(NOW, ZoneOffset.UTC));
    final MockHttpServletRequest request = new MockHttpServletRequest("POST", MockPayWebhookController.PATH);

    static final String EVENT = """
            {"id":"evt_1","type":"PAYMENT_INITIATED","created":1,"sequence":1,"data":{"orderId":"0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b","amountMinor":2599,"currency":"USD"}}""";

    ResponseEntity<?> deliver(String body, String secret) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return controller.receive(TestSigner.header(secret, NOW.getEpochSecond(), bytes), bytes, request);
    }

    double count(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("a valid event is processed: 200 {received:true}, counters, and webhook.verify/process observations")
    void applied() {
        when(events.process(any(), eq(EVENT))).thenReturn(new Result(Outcome.APPLIED, 2, 1));

        ResponseEntity<?> response = deliver(EVENT, TestSigner.CURRENT_SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("received", true));
        assertThat(count("ledgerline.webhook.received")).isEqualTo(1);
        assertThat(count("ledgerline.webhook.applied")).isEqualTo(2);
        assertThat(count("ledgerline.webhook.stale")).isEqualTo(1);
        assertThat(meters.get("ledgerline.webhook.processing").timer().count()).isEqualTo(1);
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("webhook.verify")
                .that()
                .hasLowCardinalityKeyValue("result", "valid");
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("webhook.process")
                .that()
                .hasLowCardinalityKeyValue("outcome", "APPLIED")
                .hasLowCardinalityKeyValue("event.type", "PAYMENT_INITIATED")
                .hasHighCardinalityKeyValue("event.id", "evt_1")
                .hasHighCardinalityKeyValue("order.id", "0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
    }

    @Test
    @DisplayName("a duplicate is acknowledged with duplicate=true and counted")
    void duplicate() {
        when(events.process(any(), anyString())).thenReturn(new Result(Outcome.DUPLICATE, 0, 0));

        ResponseEntity<?> response = deliver(EVENT, TestSigner.CURRENT_SECRET);

        assertThat(response.getBody()).isEqualTo(Map.of("received", true, "duplicate", true));
        assertThat(count("ledgerline.webhook.duplicates")).isEqualTo(1);
    }

    @Test
    @DisplayName("a bad signature is 400 invalid-signature, recorded as rejected, never processed")
    void badSignature() {
        ResponseEntity<?> response = deliver(EVENT, TestSigner.PREVIOUS_SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) response.getBody()).getType().toString())
                .isEqualTo("urn:ledgerline:problem:invalid-signature");
        verify(events).reject(eq("no_matching_signature"), any());
        verify(events, never()).process(any(), anyString());
        assertThat(count("ledgerline.webhook.rejected", "reason", "no_matching_signature"))
                .isEqualTo(1);
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("webhook.verify")
                .that()
                .hasLowCardinalityKeyValue("result", "no_matching_signature");
    }

    @Test
    @DisplayName("an oversized body is rejected before verification; a signed non-event is 400 malformed-event")
    void tooLargeAndMalformed() {
        ResponseEntity<?> large = controller.receive(null, new byte[64 * 1024 + 1], request);
        assertThat(large.getStatusCode().value()).isEqualTo(400);
        verify(events).reject(eq("body_too_large"), any());

        ResponseEntity<?> malformed = deliver("{\"hello\":true}", TestSigner.CURRENT_SECRET);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) malformed.getBody()).getType().toString())
                .isEqualTo("urn:ledgerline:problem:malformed-event");
        assertThat(count("ledgerline.webhook.rejected", "reason", "malformed_event"))
                .isEqualTo(1);
        verify(events, never()).process(any(), anyString());
    }

    @Test
    @DisplayName("a processing failure is counted and rethrown (500, rolled back for redelivery)")
    void failure() {
        when(events.process(any(), anyString())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> deliver(EVENT, TestSigner.CURRENT_SECRET)).isInstanceOf(IllegalStateException.class);
        assertThat(count("ledgerline.webhook.failures")).isEqualTo(1);
    }
}

package com.specshield.ledgerline.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specshield.ledgerline.application.DecodedEvent;
import com.specshield.ledgerline.application.ProviderEventDecoder.MalformedEventException;
import com.specshield.ledgerline.domain.PaymentEventType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class MockPayEventDecoderTest {

    private static final String ORDER = "0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b";
    private final MockPayEventDecoder decoder =
            new MockPayEventDecoder(JsonMapper.builder().build());

    @Test
    @DisplayName("decodes a MockPay event into a domain PaymentEvent")
    void decodes() {
        DecodedEvent e = decoder.decode("""
                {"id":"evt_abc","type":"PAYMENT_SUCCEEDED","created":1768471200,"sequence":2,
                 "data":{"orderId":"%s","amountMinor":2599,"currency":"USD"}}""".formatted(ORDER));

        assertThat(e.knownType()).isTrue();
        assertThat(e.event().type()).isEqualTo(PaymentEventType.PAYMENT_SUCCEEDED);
        assertThat(e.event().orderId()).isEqualTo(UUID.fromString(ORDER));
        assertThat(e.event().amountMinor()).isEqualTo(2599);
        assertThat(e.event().sequence()).isEqualTo(2);
        assertThat(e.event().occurredAt()).isEqualTo(Instant.ofEpochSecond(1768471200));
    }

    @Test
    @DisplayName("an unknown type decodes without a domain event, keeping the id and order id")
    void unknownType() {
        DecodedEvent e = decoder.decode(
                "{\"id\":\"evt_x\",\"type\":\"PAYMENT_DISPUTED\",\"data\":{\"orderId\":\"%s\"}}".formatted(ORDER));

        assertThat(e.knownType()).isFalse();
        assertThat(e.type()).isEqualTo("PAYMENT_DISPUTED");
        assertThat(e.orderId()).isEqualTo(UUID.fromString(ORDER));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "[]",
                "{}",
                "{\"id\":\"evt 1\",\"type\":\"PAYMENT_SUCCEEDED\"}",
                "{\"id\":\"evt_1\"}",
                "{\"id\":\"evt_1\",\"type\":\"PAYMENT_SUCCEEDED\",\"created\":1,\"sequence\":1,\"data\":{}}",
                "{\"id\":\"evt_1\",\"type\":\"PAYMENT_SUCCEEDED\",\"created\":1,\"sequence\":1,\"data\":{\"orderId\":\"0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b\",\"amountMinor\":10,\"currency\":\"EUR\"}}",
                "{\"id\":\"evt_1\",\"type\":\"PAYMENT_SUCCEEDED\",\"created\":1,\"sequence\":0,\"data\":{\"orderId\":\"0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b\",\"amountMinor\":10,\"currency\":\"USD\"}}",
                "{\"id\":\"evt_1\",\"type\":\"PAYMENT_SUCCEEDED\",\"created\":\"x\",\"sequence\":1,\"data\":{\"orderId\":\"0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b\",\"amountMinor\":10,\"currency\":\"USD\"}}",
                "{\"id\":\"evt_1\",\"type\":\"PAYMENT_SUCCEEDED\",\"created\":1,\"sequence\":1,\"data\":{\"orderId\":\"0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b\",\"amountMinor\":1.5,\"currency\":\"USD\"}}",
                "{\"id\":\"evt_1\",\"type\":\"PAYMENT_SUCCEEDED\",\"created\":1,\"sequence\":1,\"data\":{\"orderId\":\"0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b\",\"amountMinor\":0,\"currency\":\"USD\"}}"
            })
    @DisplayName("anything that is not a well-formed event is a MalformedEventException")
    void malformed(String payload) {
        assertThatThrownBy(() -> decoder.decode(payload)).isInstanceOf(MalformedEventException.class);
    }
}

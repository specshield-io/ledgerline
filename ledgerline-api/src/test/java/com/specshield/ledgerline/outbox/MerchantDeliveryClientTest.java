package com.specshield.ledgerline.outbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.application.OutboxStatus;
import com.specshield.ledgerline.webhook.TestSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP side of the relay against WireMock: statuses, timeouts, refused connections, the signed request. */
class MerchantDeliveryClientTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    static final WireMockServer merchant =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    static void start() {
        merchant.start();
    }

    @AfterAll
    static void stop() {
        merchant.stop();
    }

    @BeforeEach
    void reset() {
        merchant.resetAll();
    }

    MerchantDeliveryClient client(String url) {
        MerchantProperties properties = new MerchantProperties(
                url, List.of(TestSigner.MERCHANT_SECRET), Duration.ofMillis(500), Duration.ofMillis(300));
        return new MerchantDeliveryClient(
                properties,
                new MockEnvironment().withProperty("merchant.port", Integer.toString(merchant.port())),
                Clock.fixed(NOW, ZoneOffset.UTC),
                json);
    }

    MerchantDeliveryClient client() {
        return client("http://localhost:${merchant.port}/hook");
    }

    static OutboxMessage message() {
        return new OutboxMessage(
                42,
                UUID.fromString("0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b"),
                "order.paid",
                "{\"status\":\"PAID\"}",
                OutboxStatus.IN_FLIGHT,
                2,
                NOW,
                null,
                NOW,
                null,
                NOW);
    }

    void answer(int status) {
        merchant.stubFor(
                WireMock.post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(status)));
    }

    @Test
    @DisplayName("2xx is delivered; the request is signed, carries the event id and the attempt number")
    void delivered() {
        answer(202);

        assertThat(client().deliver(message())).isEqualTo(new DeliveryResult.Delivered(202));

        LoggedRequest request =
                merchant.findAll(postRequestedFor(urlEqualTo("/hook"))).getFirst();
        assertThat(request.getHeader("Ledgerline-Event-Id")).isEqualTo("42");
        assertThat(request.getHeader("Ledgerline-Delivery-Attempt")).isEqualTo("3");
        assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
        long t = NOW.getEpochSecond();
        assertThat(request.getHeader("Ledgerline-Signature"))
                .isEqualTo(TestSigner.header(TestSigner.MERCHANT_SECRET, t, request.getBody()));
        var envelope = json.readTree(request.getBody());
        assertThat(envelope.get("id").asString()).isEqualTo("42");
        assertThat(envelope.get("type").asString()).isEqualTo("order.paid");
        assertThat(envelope.get("data").get("status").asString()).isEqualTo("PAID");
        assertThat(client().body(message())).isEqualTo(request.getBody());
    }

    @Test
    @DisplayName("statuses are classified: 5xx, 4xx and anything else (redirects are not followed)")
    void statuses() {
        answer(503);
        assertThat(client().deliver(message()))
                .isEqualTo(new DeliveryResult.Failed("http_5xx", "HTTP 503 from merchant endpoint"));
        answer(404);
        assertThat(client().deliver(message()))
                .isEqualTo(new DeliveryResult.Failed("http_4xx", "HTTP 404 from merchant endpoint"));
        answer(302);
        assertThat(client().deliver(message()))
                .isEqualTo(new DeliveryResult.Failed("http_other", "HTTP 302 from merchant endpoint"));
        answer(199 + 1);
        assertThat(client().deliver(message())).isInstanceOf(DeliveryResult.Delivered.class);
    }

    @Test
    @DisplayName("no response within the request timeout is a timeout failure")
    void timeout() {
        merchant.stubFor(WireMock.post(urlEqualTo("/hook"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));

        assertThat(client().deliver(message()))
                .isEqualTo(new DeliveryResult.Failed("timeout", "no response within 300 ms"));
    }

    @Test
    @DisplayName("a refused connection is a connect failure")
    void refused() {
        DeliveryResult result = client("http://127.0.0.1:9/hook").deliver(message());

        assertThat(result).isInstanceOf(DeliveryResult.Failed.class);
        assertThat(((DeliveryResult.Failed) result).reason()).isIn("connect", "timeout");
    }
}

package com.specshield.ledgerline.mockpay;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.webhook.WebhookSigner;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scripted MockPay: the chaos scenarios decide exactly which events MockPay sends, in which order, and how they are
 * damaged. Each event is a real signed HTTP POST to {@code /webhooks/mockpay}, made once (no retry), so the receiver's
 * answer to that delivery can be shown. Synthetic: no real payment network is involved.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.mockpay.enabled", havingValue = "true")
public class MockPayScripts {

    /** How a delivery is damaged on its way to the receiver. */
    public enum Tamper {
        NONE,
        /** Signed correctly, then the amount in the body is changed (a man-in-the-middle edit). */
        BODY_ALTERED_AFTER_SIGNING,
        /** Correctly signed, but with a timestamp 10 minutes old (a captured delivery replayed later). */
        STALE_TIMESTAMP
    }

    static final Duration REPLAY_AGE = Duration.ofMinutes(10);

    /** A payment MockPay has opened for an order. */
    public record Payment(String paymentId, Order order) {}

    /** @param status the receiver's HTTP status, or -1 when it could not be reached */
    public record Delivery(String eventId, int status) {}

    private final MockPayProperties properties;
    private final MockPayLedger ledger;
    private final Environment environment;
    private final Clock clock;
    private final JsonMapper json;
    private final WebhookSigner signer;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    public MockPayScripts(
            MockPayProperties properties, MockPayLedger ledger, Environment environment, Clock clock, JsonMapper json) {
        this.properties = properties;
        this.ledger = ledger;
        this.environment = environment;
        this.clock = clock;
        this.json = json;
        this.signer = new WebhookSigner(properties.signingSecrets());
        this.http = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Records a PENDING payment in MockPay's ledger. */
    public Payment open(Order order) {
        String paymentId = "mp_" + randomHex(12);
        ledger.record(paymentId, order.id(), order.amountMinor(), order.currency(), 1, clock.instant());
        return new Payment(paymentId, order);
    }

    /** MockPay's own record of the outcome, as it does just before sending the outcome event. */
    public void settle(Payment payment, boolean succeeded) {
        ledger.settle(
                payment.paymentId(),
                succeeded ? MockPayLedger.Status.SUCCEEDED : MockPayLedger.Status.FAILED,
                clock.instant());
    }

    public String newEventId() {
        return "evt_" + randomHex(12);
    }

    /**
     * Sends one event.
     *
     * @param amountMinor the order amount for payment events, the refunded amount for REFUND_SUCCEEDED
     * @param correlationId sent as X-Request-Id, so the receiver's log lines and audit rows share the run's id
     */
    public Delivery send(
            Payment payment,
            String eventId,
            String type,
            long sequence,
            long amountMinor,
            Tamper tamper,
            String correlationId) {
        byte[] body = body(payment, eventId, type, sequence, amountMinor);
        long timestamp = clock.instant().getEpochSecond();
        if (tamper == Tamper.STALE_TIMESTAMP) {
            timestamp -= REPLAY_AGE.toSeconds();
        }
        String signature = signer.sign(body, timestamp);
        if (tamper == Tamper.BODY_ALTERED_AFTER_SIGNING) {
            body = body(payment, eventId, type, sequence, 1); // the "attacker" changes the amount to 1 cent
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(environment.resolveRequiredPlaceholders(properties.webhookUrl())))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("MockPay-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (correlationId != null) {
            request.header("X-Request-Id", correlationId);
        }
        try {
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            return new Delivery(eventId, status);
        } catch (IOException e) {
            return new Delivery(eventId, -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery(eventId, -1);
        }
    }

    private byte[] body(Payment payment, String eventId, String type, long sequence, long amountMinor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", payment.order().id().toString());
        data.put("amountMinor", amountMinor);
        data.put("currency", payment.order().currency());
        data.put("paymentId", payment.paymentId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", eventId);
        body.put("type", type);
        body.put("created", clock.instant().getEpochSecond());
        body.put("sequence", sequence);
        body.put("data", data);
        return json.writeValueAsBytes(body);
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        http.close();
    }
}

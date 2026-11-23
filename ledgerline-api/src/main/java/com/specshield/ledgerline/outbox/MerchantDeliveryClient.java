package com.specshield.ledgerline.outbox;

import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.webhook.WebhookSigner;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one outbox row to the merchant endpoint as a signed POST.
 *
 * <p>Body: {@code {"id":"<outbox id>","type":"order.paid","createdAt":"...","data":{...}}}, built only from the row,
 * so every attempt for a row sends the same bytes. Headers: {@code Ledgerline-Signature: t=..,v1=..} (HMAC-SHA256
 * over {@code t + "." + body}, the scheme MockPay uses, with the merchant's own secret), {@code Ledgerline-Event-Id}
 * (the outbox id; the receiver's dedupe key) and {@code Ledgerline-Delivery-Attempt} (1-based).
 */
@Component
public class MerchantDeliveryClient {

    public static final String SIGNATURE_HEADER = "Ledgerline-Signature";
    public static final String EVENT_ID_HEADER = "Ledgerline-Event-Id";
    public static final String ATTEMPT_HEADER = "Ledgerline-Delivery-Attempt";

    private final MerchantProperties properties;
    private final WebhookSigner signer;
    private final Environment environment;
    private final Clock clock;
    private final JsonMapper json;
    private final HttpClient http;

    public MerchantDeliveryClient(
            MerchantProperties properties, Environment environment, Clock clock, JsonMapper json) {
        this.properties = properties;
        this.signer = new WebhookSigner(properties.signingSecrets());
        this.environment = environment;
        this.clock = clock;
        this.json = json;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    public DeliveryResult deliver(OutboxMessage message) {
        byte[] body = body(message);
        int attempt = message.attempts() + 1;
        HttpRequest request = HttpRequest.newBuilder(URI.create(environment.resolvePlaceholders(properties.url())))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, signer.sign(body, clock.instant().getEpochSecond()))
                .header(EVENT_ID_HEADER, Long.toString(message.id()))
                .header(ATTEMPT_HEADER, Integer.toString(attempt))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new DeliveryResult.Delivered(status);
            }
            String reason = status >= 500 ? "http_5xx" : status >= 400 ? "http_4xx" : "http_other";
            return new DeliveryResult.Failed(reason, "HTTP " + status + " from merchant endpoint");
        } catch (HttpConnectTimeoutException e) {
            return new DeliveryResult.Failed(
                    "timeout",
                    "connect timeout after " + properties.connectTimeout().toMillis() + " ms");
        } catch (HttpTimeoutException e) {
            return new DeliveryResult.Failed(
                    "timeout",
                    "no response within " + properties.requestTimeout().toMillis() + " ms");
        } catch (ConnectException e) {
            return new DeliveryResult.Failed(
                    "connect", "connection failed: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            return new DeliveryResult.Failed("io", "I/O error: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult.Failed("interrupted", "delivery interrupted (shutdown)");
        }
    }

    /** The exact bytes sent for a row; identical on every attempt. */
    byte[] body(OutboxMessage message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", Long.toString(message.id()));
        envelope.put("type", message.eventType());
        envelope.put("createdAt", message.createdAt().toString());
        envelope.put("data", json.readTree(message.payloadJson()));
        return json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
    }
}

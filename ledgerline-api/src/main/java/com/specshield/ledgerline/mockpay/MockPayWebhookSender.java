package com.specshield.ledgerline.mockpay;

import com.specshield.ledgerline.webhook.WebhookSigner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers one signed event, retrying with exponential backoff when the receiver answers 5xx or cannot be reached
 * (as real providers do). 2xx and 4xx end the attempts. Runs on the caller's (virtual) thread.
 */
final class MockPayWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(MockPayWebhookSender.class);

    private final HttpClient http;
    private final WebhookSigner signer;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration backoff;

    MockPayWebhookSender(HttpClient http, WebhookSigner signer, Clock clock, int maxAttempts, Duration backoff) {
        this.http = http;
        this.signer = signer;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    /** Returns the final HTTP status, or -1 if every attempt failed to connect. */
    int deliver(URI url, String eventId, byte[] body) throws InterruptedException {
        int status = -1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header(
                            "MockPay-Signature",
                            signer.sign(body, clock.instant().getEpochSecond()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                status = http.send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode();
                if (status < 500) {
                    log.info("MockPay delivered {} (attempt {}): HTTP {}", eventId, attempt, status);
                    return status;
                }
            } catch (IOException e) {
                log.info(
                        "MockPay could not deliver {} (attempt {}): {}",
                        eventId,
                        attempt,
                        e.getClass().getSimpleName());
            }
            if (attempt < maxAttempts) {
                Thread.sleep(backoff.multipliedBy(1L << (attempt - 1)));
            }
        }
        log.error("MockPay gave up on {} after {} attempts (last status {})", eventId, maxAttempts, status);
        return status;
    }
}

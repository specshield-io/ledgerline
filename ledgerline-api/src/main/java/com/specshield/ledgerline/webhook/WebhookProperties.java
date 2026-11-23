package com.specshield.ledgerline.webhook;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Receiver settings for MockPay webhooks.
 *
 * @param secrets signing secrets accepted; configure two during rotation (old and new), then drop the old one
 * @param tolerance maximum difference between the signature timestamp and our clock
 * @param orphanRetryWindow how long ORPHAN events are retried
 */
@Validated
@ConfigurationProperties("ledgerline.webhook.mockpay")
public record WebhookProperties(
        @NotEmpty List<@Size(min = 32, message = "webhook secrets must be at least 32 characters") String> secrets,
        Duration tolerance,
        Duration orphanRetryWindow) {

    public WebhookProperties {
        tolerance = tolerance == null ? Duration.ofSeconds(300) : tolerance;
        orphanRetryWindow = orphanRetryWindow == null ? Duration.ofHours(1) : orphanRetryWindow;
    }

    @Override
    public String toString() {
        return "WebhookProperties[secrets=<" + (secrets == null ? 0 : secrets.size()) + " redacted>, tolerance="
                + tolerance + ", orphanRetryWindow=" + orphanRetryWindow + "]";
    }
}

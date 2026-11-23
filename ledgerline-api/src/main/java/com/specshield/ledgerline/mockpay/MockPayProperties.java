package com.specshield.ledgerline.mockpay;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The simulated payment provider. Synthetic: it never contacts a real payment network.
 *
 * @param enabled when false, a stub gateway is used and no webhooks are sent
 * @param webhookUrl where events are POSTed; placeholders (e.g. {@code ${local.server.port}}) are resolved at send time
 * @param signingSecrets secrets to sign with; two during rotation (one v1 entry each)
 * @param defaultOutcome PAYMENT_SUCCEEDED or PAYMENT_FAILED
 * @param minDelay lower bound of the delay before the outcome event
 * @param maxDelay upper bound of that delay
 * @param maxDeliveryAttempts attempts per event when the receiver answers 5xx or is unreachable
 * @param retryBackoff first retry delay, doubled on every further attempt
 */
@ConfigurationProperties("ledgerline.mockpay")
public record MockPayProperties(
        boolean enabled,
        String webhookUrl,
        List<String> signingSecrets,
        String defaultOutcome,
        Duration minDelay,
        Duration maxDelay,
        int maxDeliveryAttempts,
        Duration retryBackoff) {

    public MockPayProperties {
        signingSecrets = signingSecrets == null ? List.of() : List.copyOf(signingSecrets);
        defaultOutcome = defaultOutcome == null ? "PAYMENT_SUCCEEDED" : defaultOutcome;
        minDelay = minDelay == null ? Duration.ofMillis(300) : minDelay;
        maxDelay = maxDelay == null ? Duration.ofMillis(800) : maxDelay;
        maxDeliveryAttempts = maxDeliveryAttempts <= 0 ? 5 : maxDeliveryAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofMillis(250) : retryBackoff;
        if (!defaultOutcome.equals("PAYMENT_SUCCEEDED") && !defaultOutcome.equals("PAYMENT_FAILED")) {
            throw new IllegalArgumentException(
                    "ledgerline.mockpay.default-outcome must be PAYMENT_SUCCEEDED or PAYMENT_FAILED");
        }
        if (maxDelay.compareTo(minDelay) < 0) {
            throw new IllegalArgumentException("ledgerline.mockpay.max-delay must not be below min-delay");
        }
    }

    @Override
    public String toString() {
        return "MockPayProperties[enabled=" + enabled + ", webhookUrl=" + webhookUrl + ", signingSecrets=<"
                + signingSecrets.size() + " redacted>, defaultOutcome=" + defaultOutcome + "]";
    }
}

package com.specshield.ledgerline.webhook;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The built-in demo merchant that receives outbox notifications.
 *
 * @param enabled expose {@code POST /demo/merchant-sink}; off in prod
 * @param secrets secrets accepted for {@code Ledgerline-Signature} (the relay's signing secrets)
 * @param tolerance maximum difference between the signature timestamp and our clock
 * @param capacity deliveries kept in memory for inspection
 */
@ConfigurationProperties("ledgerline.merchant.sink")
public record MerchantSinkProperties(boolean enabled, List<String> secrets, Duration tolerance, int capacity) {

    public MerchantSinkProperties {
        secrets = secrets == null ? List.of() : List.copyOf(secrets);
        tolerance = tolerance == null ? Duration.ofSeconds(300) : tolerance;
        capacity = capacity <= 0 ? 500 : capacity;
    }

    @Override
    public String toString() {
        return "MerchantSinkProperties[enabled=" + enabled + ", secrets=<" + secrets.size() + " redacted>, tolerance="
                + tolerance + ", capacity=" + capacity + "]";
    }
}

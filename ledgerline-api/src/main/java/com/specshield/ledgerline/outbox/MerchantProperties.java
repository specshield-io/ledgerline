package com.specshield.ledgerline.outbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The merchant endpoint that receives order notifications.
 *
 * @param url where notifications are POSTed; placeholders (e.g. {@code ${local.server.port}}) are resolved at send time
 * @param signingSecrets secrets for {@code Ledgerline-Signature}, separate from MockPay's; two during rotation
 * @param connectTimeout TCP connect timeout
 * @param requestTimeout time allowed for the response headers once connected
 */
@Validated
@ConfigurationProperties("ledgerline.merchant")
public record MerchantProperties(
        @NotBlank String url,

        @NotEmpty
        List<@Size(min = 32, message = "merchant signing secrets must be at least 32 characters") String>
                signingSecrets,

        Duration connectTimeout,
        Duration requestTimeout) {

    public MerchantProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
    }

    @Override
    public String toString() {
        return "MerchantProperties[url=" + url + ", signingSecrets=<"
                + (signingSecrets == null ? 0 : signingSecrets.size()) + " redacted>, connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout + "]";
    }
}

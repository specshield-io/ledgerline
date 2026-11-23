package com.specshield.ledgerline.web;

import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per-client-IP request limits.
 *
 * @param enabled off only in tests that are not about rate limiting
 * @param publicPerMinute requests per minute to the API and its documentation
 * @param chaosPerMinute chaos scenario starts per minute
 * @param trustedProxies CIDR ranges of reverse proxies (Fly.io, Cloudflare) whose {@code X-Forwarded-For} is trusted;
 *     empty means the header is ignored and the TCP peer address is the client
 */
@Validated
@ConfigurationProperties("ledgerline.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(1) int publicPerMinute,
        @Min(1) int chaosPerMinute,
        List<String> trustedProxies) {

    public RateLimitProperties {
        publicPerMinute = publicPerMinute == 0 ? 240 : publicPerMinute;
        chaosPerMinute = chaosPerMinute == 0 ? 10 : chaosPerMinute;
        trustedProxies = trustedProxies == null
                ? List.of()
                : trustedProxies.stream()
                        .filter(p -> p != null && !p.isBlank())
                        .map(String::trim)
                        .toList();
    }
}

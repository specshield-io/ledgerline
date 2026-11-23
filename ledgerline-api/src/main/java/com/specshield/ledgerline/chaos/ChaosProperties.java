package com.specshield.ledgerline.chaos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxConcurrentRuns runs in progress at once, across all clients; more are refused with 429
 * @param merchantOutage how long merchant-down and merchant-slow keep the demo merchant degraded
 * @param merchantSlowDelay how long the slow merchant takes to answer
 * @param awaitTimeout how long a run waits for an order to reach a status (real time)
 */
@Validated
@ConfigurationProperties("ledgerline.chaos")
public record ChaosProperties(
        @Min(1) @Max(20) int maxConcurrentRuns,
        Duration merchantOutage,
        Duration merchantSlowDelay,
        Duration awaitTimeout) {

    public ChaosProperties {
        maxConcurrentRuns = maxConcurrentRuns == 0 ? 3 : maxConcurrentRuns;
        merchantOutage = merchantOutage == null ? Duration.ofMinutes(2) : merchantOutage;
        merchantSlowDelay = merchantSlowDelay == null ? Duration.ofSeconds(10) : merchantSlowDelay;
        awaitTimeout = awaitTimeout == null ? Duration.ofSeconds(15) : awaitTimeout;
    }
}

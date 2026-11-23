package com.specshield.ledgerline.outbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbox relay settings.
 *
 * @param dispatcherEnabled run the dispatcher and the watchdog on a schedule (tests call them directly instead)
 * @param pollInterval delay between dispatcher runs
 * @param batchSize rows claimed per run
 * @param backoffBase base of the exponential backoff
 * @param backoffCap upper bound of a single backoff delay
 * @param maxAttempts delivery attempts before a row is DEAD
 * @param stuckAfter IN_FLIGHT rows claimed longer ago than this are returned to PENDING by the watchdog
 * @param watchdogInterval delay between watchdog runs
 */
@Validated
@ConfigurationProperties("ledgerline.outbox")
public record OutboxProperties(
        boolean dispatcherEnabled,
        Duration pollInterval,
        @Min(1) @Max(500) int batchSize,
        Duration backoffBase,
        Duration backoffCap,
        @Min(1) @Max(50) int maxAttempts,
        Duration stuckAfter,
        Duration watchdogInterval) {

    public OutboxProperties {
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
        batchSize = batchSize == 0 ? 20 : batchSize;
        backoffBase = backoffBase == null ? Duration.ofSeconds(2) : backoffBase;
        backoffCap = backoffCap == null ? Duration.ofHours(1) : backoffCap;
        maxAttempts = maxAttempts == 0 ? 8 : maxAttempts;
        stuckAfter = stuckAfter == null ? Duration.ofSeconds(60) : stuckAfter;
        watchdogInterval = watchdogInterval == null ? Duration.ofSeconds(10) : watchdogInterval;
    }
}

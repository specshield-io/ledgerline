package com.specshield.ledgerline.outbox;

import com.specshield.ledgerline.application.OutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Crash recovery: a dispatcher that dies between claiming a row and recording its result leaves the row IN_FLIGHT.
 * Rows claimed longer than {@code stuckAfter} ago go back to PENDING, due now. The attempt count is unchanged (the
 * lost attempt may or may not have reached the merchant), which is why delivery is at-least-once.
 *
 * <p>{@code stuckAfter} (60 s) must stay well above the longest possible attempt (connect 2 s + response 3 s), or
 * healthy deliveries would be released and sent twice.
 */
@Component
public class OutboxWatchdog {

    private static final Logger log = LoggerFactory.getLogger(OutboxWatchdog.class);

    private final OutboxStore store;
    private final OutboxMetrics metrics;
    private final Clock clock;
    private final Duration stuckAfter;

    public OutboxWatchdog(OutboxStore store, OutboxMetrics metrics, Clock clock, OutboxProperties properties) {
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
        this.stuckAfter = properties.stuckAfter();
    }

    /** Returns the number of rows released. */
    public int releaseStuck() {
        Instant now = clock.instant();
        int released = store.releaseStuck(
                now.minus(stuckAfter),
                "released by watchdog: IN_FLIGHT longer than " + stuckAfter.toSeconds() + " s",
                now);
        if (released > 0) {
            metrics.released(released);
            log.warn("outbox watchdog returned {} stuck IN_FLIGHT row(s) to PENDING", released);
        }
        return released;
    }
}

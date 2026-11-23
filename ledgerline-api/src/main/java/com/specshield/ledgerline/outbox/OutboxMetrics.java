package com.specshield.ledgerline.outbox;

import com.specshield.ledgerline.application.OutboxStatus;
import com.specshield.ledgerline.application.OutboxStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox metrics. Prometheus names: gauges {@code ledgerline_outbox_pending} and
 * {@code ledgerline_outbox_oldest_pending_age_seconds} (0 when nothing is pending); counters
 * {@code ledgerline_outbox_delivered_total}, {@code ledgerline_outbox_failed_total{reason}},
 * {@code ledgerline_outbox_dead_total}, {@code ledgerline_outbox_watchdog_released_total}; timer
 * {@code ledgerline_outbox_delivery_seconds{outcome}} (HTTP latency of each attempt).
 *
 * <p>The gauges query the database when scraped (two indexed queries); a failing query reports NaN rather than
 * breaking the scrape.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final MeterRegistry registry;
    private final Counter delivered;
    private final Counter dead;
    private final Counter released;

    public OutboxMetrics(MeterRegistry registry, OutboxStore store, Clock clock) {
        this.registry = registry;
        this.delivered = Counter.builder("ledgerline.outbox.delivered")
                .description("Outbox rows delivered (2xx)")
                .register(registry);
        this.dead = Counter.builder("ledgerline.outbox.dead")
                .description("Outbox rows that ran out of attempts")
                .register(registry);
        this.released = Counter.builder("ledgerline.outbox.watchdog.released")
                .description("IN_FLIGHT rows returned to PENDING by the watchdog")
                .register(registry);
        Gauge.builder("ledgerline.outbox.pending", store, s -> safely(() -> s.countByStatus(OutboxStatus.PENDING)))
                .description("Outbox rows waiting for delivery or retry")
                .register(registry);
        Gauge.builder(
                        "ledgerline.outbox.oldest.pending.age",
                        store,
                        s -> safely(() -> s.oldestPendingCreatedAt()
                                .map(t -> Duration.between(t, clock.instant()).toMillis() / 1000.0)
                                .orElse(0.0)))
                .description("Age of the oldest PENDING row")
                .baseUnit("seconds")
                .register(registry);
    }

    void delivered(Duration latency) {
        delivered.increment();
        timer("delivered").record(latency);
    }

    void failed(String reason, Duration latency) {
        registry.counter("ledgerline.outbox.failed", "reason", reason).increment();
        timer("failed").record(latency);
    }

    void dead() {
        dead.increment();
    }

    void released(int rows) {
        released.increment(rows);
    }

    private Timer timer(String outcome) {
        return Timer.builder("ledgerline.outbox.delivery")
                .description("HTTP latency of one delivery attempt")
                .tag("outcome", outcome)
                .register(registry);
    }

    private interface Query {
        double get();
    }

    private static double safely(Query query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            log.warn("outbox gauge query failed: {}", e.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}

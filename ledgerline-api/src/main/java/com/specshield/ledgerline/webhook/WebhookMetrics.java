package com.specshield.ledgerline.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Webhook metrics. Prometheus names: {@code ledgerline_webhook_received_total}, {@code _applied_total},
 * {@code _duplicates_total}, {@code _stale_total}, {@code _rejected_total{reason}}, {@code _failures_total} and the
 * timer {@code ledgerline_webhook_processing_seconds}. All tagged {@code provider}.
 */
@Component
public class WebhookMetrics {

    private static final String PROVIDER = "provider";

    private final MeterRegistry registry;
    private final String provider = MockPayEventDecoder.PROVIDER;
    private final Counter received;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter stale;
    private final Counter failures;
    private final Timer processing;

    public WebhookMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.received = counter("ledgerline.webhook.received", "Webhook deliveries received");
        this.applied = counter("ledgerline.webhook.applied", "Provider events applied to orders");
        this.duplicates = counter("ledgerline.webhook.duplicates", "Deliveries of an already stored event");
        this.stale = counter("ledgerline.webhook.stale", "Provider events found stale");
        this.failures = counter("ledgerline.webhook.failures", "Deliveries that failed with a server error");
        this.processing = Timer.builder("ledgerline.webhook.processing")
                .description("Time to verify and process a delivery")
                .tag(PROVIDER, provider)
                .register(registry);
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name)
                .description(description)
                .tag(PROVIDER, provider)
                .register(registry);
    }

    void received() {
        received.increment();
    }

    void applied(int count) {
        applied.increment(count);
    }

    void duplicate() {
        duplicates.increment();
    }

    void stale(int count) {
        stale.increment(count);
    }

    void failure() {
        failures.increment();
    }

    void rejected(String reason) {
        Counter.builder("ledgerline.webhook.rejected")
                .description("Deliveries rejected (signature or format)")
                .tag(PROVIDER, provider)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    Timer processing() {
        return processing;
    }
}

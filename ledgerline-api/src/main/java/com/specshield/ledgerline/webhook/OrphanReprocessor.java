package com.specshield.ledgerline.webhook;

import com.specshield.ledgerline.application.PaymentEventService;
import com.specshield.ledgerline.application.PaymentInbox;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries ORPHAN events (received before their order existed) every 30 seconds, for the configured window
 * (default 1 hour) after they were received. Older orphans stay ORPHAN for manual follow-up.
 */
@Component
public class OrphanReprocessor {

    private static final Logger log = LoggerFactory.getLogger(OrphanReprocessor.class);

    private final PaymentInbox inbox;
    private final PaymentEventService events;
    private final WebhookProperties properties;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public OrphanReprocessor(
            PaymentInbox inbox,
            PaymentEventService events,
            WebhookProperties properties,
            WebhookMetrics metrics,
            Clock clock) {
        this.inbox = inbox;
        this.events = events;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Returns the number of events applied. */
    @Scheduled(cron = "${ledgerline.webhook.mockpay.orphan-cron:*/30 * * * * *}", zone = "UTC")
    public int run() {
        Instant since = clock.instant().minus(properties.orphanRetryWindow());
        int applied = 0;
        for (UUID orderId : inbox.orphanOrderIds(since)) {
            try {
                PaymentEventService.Result result = events.reprocessOrphans(orderId, since);
                applied += result.applied();
                metrics.applied(result.applied());
                metrics.stale(result.stale());
            } catch (RuntimeException e) {
                log.error("orphan reprocessing failed for order {}", orderId, e);
            }
        }
        if (applied > 0) {
            log.info("orphan reprocessor applied {} event(s)", applied);
        }
        return applied;
    }
}

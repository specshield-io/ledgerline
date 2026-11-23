package com.specshield.ledgerline.outbox;

import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.application.OutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Relays outbox rows to the merchant endpoint.
 *
 * <p>One run: claim up to {@code batchSize} due PENDING rows ({@code FOR UPDATE SKIP LOCKED}, marked IN_FLIGHT,
 * committed), then deliver them concurrently on virtual threads, outside any transaction, and record each result:
 *
 * <ul>
 *   <li>2xx: DELIVERED with {@code delivered_at};
 *   <li>anything else (status, timeout, I/O): one more attempt, {@code last_error}, and either PENDING with
 *       {@code next_attempt_at = now + backoff} or DEAD once the attempts are used up.
 * </ul>
 *
 * <p>Several dispatchers (threads or instances) can run at once: SKIP LOCKED gives each a disjoint set of rows. A
 * crash between claim and result leaves the row IN_FLIGHT until {@link OutboxWatchdog} returns it, so the row may be
 * delivered again: delivery is at-least-once and receivers deduplicate by {@code Ledgerline-Event-Id}.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final MerchantDeliveryClient client;
    private final BackoffPolicy backoff;
    private final OutboxMetrics metrics;
    private final Clock clock;
    private final int batchSize;

    public OutboxDispatcher(
            OutboxStore store,
            MerchantDeliveryClient client,
            BackoffPolicy backoff,
            OutboxMetrics metrics,
            Clock clock,
            OutboxProperties properties) {
        this.store = store;
        this.client = client;
        this.backoff = backoff;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = properties.batchSize();
    }

    /** Claims and delivers one batch. Returns the number of rows claimed. */
    public int dispatchOnce() {
        List<OutboxMessage> batch = store.claimDue(batchSize, clock.instant());
        if (batch.isEmpty()) {
            return 0;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (OutboxMessage message : batch) {
                executor.submit(() -> deliver(message));
            }
        } // close() waits for every delivery of the batch
        return batch.size();
    }

    private void deliver(OutboxMessage message) {
        long started = System.nanoTime();
        DeliveryResult result;
        try {
            result = client.deliver(message);
        } catch (RuntimeException e) {
            result = new DeliveryResult.Failed(
                    "io", "delivery failed: " + e.getClass().getSimpleName());
        }
        Duration latency = Duration.ofNanos(System.nanoTime() - started);
        Instant now = clock.instant();
        int attempt = message.attempts() + 1;
        boolean recorded;
        switch (result) {
            case DeliveryResult.Delivered d -> {
                recorded = store.markDelivered(message.id(), message.claimedAt(), now);
                metrics.delivered(latency);
            }
            case DeliveryResult.Failed f -> {
                metrics.failed(f.reason(), latency);
                recorded = switch (backoff.next(attempt + 1)) {
                    case BackoffPolicy.Retry r ->
                        store.markRetry(message.id(), message.claimedAt(), f.message(), now.plus(r.delay()));
                    case BackoffPolicy.Dead x -> {
                        boolean dead = store.markDead(message.id(), message.claimedAt(), f.message());
                        if (dead) {
                            metrics.dead();
                            log.warn(
                                    "outbox message {} ({}) is DEAD after {} attempts: {}",
                                    message.id(),
                                    message.eventType(),
                                    attempt,
                                    f.message());
                        }
                        yield dead;
                    }
                };
            }
        }
        if (!recorded) {
            // The watchdog released the row while we were delivering; its newer state wins.
            log.info(
                    "outbox message {}: result of attempt {} not recorded, the claim was released",
                    message.id(),
                    attempt);
        }
    }
}

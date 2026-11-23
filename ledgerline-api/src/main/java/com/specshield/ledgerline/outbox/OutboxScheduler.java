package com.specshield.ledgerline.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the dispatcher and the watchdog. Off when {@code ledgerline.outbox.dispatcher-enabled=false} (tests). */
@Component
@ConditionalOnProperty(name = "ledgerline.outbox.dispatcher-enabled", havingValue = "true")
class OutboxScheduler {

    /** Upper bound on batches per tick, so one tick cannot run forever under a large backlog. */
    static final int MAX_BATCHES_PER_TICK = 10;

    private final OutboxDispatcher dispatcher;
    private final OutboxWatchdog watchdog;
    private final int batchSize;

    OutboxScheduler(OutboxDispatcher dispatcher, OutboxWatchdog watchdog, OutboxProperties properties) {
        this.dispatcher = dispatcher;
        this.watchdog = watchdog;
        this.batchSize = properties.batchSize();
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.poll-interval:500ms}")
    void dispatch() {
        // A full batch suggests a backlog: keep going instead of waiting for the next tick.
        for (int i = 0; i < MAX_BATCHES_PER_TICK && dispatcher.dispatchOnce() == batchSize; i++) {
            // next batch
        }
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.watchdog-interval:10s}")
    void watchdog() {
        watchdog.releaseStuck();
    }
}

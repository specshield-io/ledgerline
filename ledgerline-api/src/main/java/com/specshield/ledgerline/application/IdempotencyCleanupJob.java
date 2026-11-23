package com.specshield.ledgerline.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes expired idempotency keys hourly. The cron is configurable; "-" disables it (used in tests). */
@Component
public class IdempotencyCleanupJob {

    private final IdempotencyService idempotency;

    public IdempotencyCleanupJob(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    @Scheduled(cron = "${ledgerline.idempotency.cleanup-cron:0 0 * * * *}", zone = "UTC")
    public int run() {
        return idempotency.purgeExpired();
    }
}

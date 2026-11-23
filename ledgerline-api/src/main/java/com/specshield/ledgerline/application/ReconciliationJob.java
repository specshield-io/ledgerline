package com.specshield.ledgerline.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs reconciliation every 5 minutes (UTC cron, configurable; "-" disables it). */
@Component
public class ReconciliationJob {

    private final ReconciliationService reconciliation;

    public ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${ledgerline.reconciliation.cron:0 */5 * * * *}", zone = "UTC")
    public int run() {
        return reconciliation.reconcile();
    }
}

package com.specshield.ledgerline.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param seedOnStartup seed 25 synthetic orders when the orders table is empty at startup
 * @param resetCron when the demo data is wiped and reseeded (UTC); "-" disables the job
 * @param dataResetsAt the same schedule in words, shown by {@code GET /api/v1/meta}
 * @param gitSha commit the running build was made from, if the build passed it in
 */
@ConfigurationProperties("ledgerline.demo")
public record DemoProperties(boolean seedOnStartup, String resetCron, String dataResetsAt, String gitSha) {

    public DemoProperties {
        resetCron = resetCron == null ? "0 0 3 * * *" : resetCron;
        dataResetsAt = dataResetsAt == null ? "03:00 UTC" : dataResetsAt;
        gitSha = gitSha == null || gitSha.isBlank() ? "unknown" : gitSha;
    }
}

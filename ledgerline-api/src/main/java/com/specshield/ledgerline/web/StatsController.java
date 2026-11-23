package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OutboxStatus;
import com.specshield.ledgerline.application.OutboxStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Predicate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reliability counters for the operations console, read from the same Micrometer meters that
 * {@code /actuator/prometheus} exports (which needs a VIEWER account), so the two never disagree.
 */
@RestController
@Tag(name = "Meta", description = "About this deployment")
public class StatsController {

    private final MeterRegistry meters;
    private final OutboxStore outbox;
    private final Instant countingSince;

    public StatsController(MeterRegistry meters, OutboxStore outbox, Clock clock) {
        this.meters = meters;
        this.outbox = outbox;
        this.countingSince = clock.instant();
    }

    @Schema(name = "Stats", description = "Webhook counters since countingSince, outbox state now")
    public record StatsResponse(
            @Schema(description = "Webhook deliveries received")
            long received,

            @Schema(description = "Provider events applied to orders")
            long applied,

            @Schema(description = "Deliveries of an already stored event, acknowledged and not applied again")
            long duplicatesBlocked,

            @Schema(description = "Deliveries refused for a missing, invalid or expired signature")
            long signaturesRejected,

            @Schema(description = "Merchant notifications waiting for delivery or retry")
            long outboxPending,

            @Schema(description = "Merchant notifications that ran out of attempts")
            long outboxDead,

            @Schema(description = "Counters are per instance and start at zero when it starts")
            Instant countingSince) {}

    @GetMapping("/api/v1/stats")
    @Operation(summary = "Reliability counters for dashboards")
    @Transactional(readOnly = true)
    StatsResponse stats() {
        return new StatsResponse(
                sum("ledgerline.webhook.received", c -> true),
                sum("ledgerline.webhook.applied", c -> true),
                sum("ledgerline.webhook.duplicates", c -> true),
                // Malformed bodies that were validly signed are not signature failures.
                sum(
                        "ledgerline.webhook.rejected",
                        c -> !"malformed_event".equals(c.getId().getTag("reason"))),
                outbox.countByStatus(OutboxStatus.PENDING),
                outbox.countByStatus(OutboxStatus.DEAD),
                countingSince);
    }

    private long sum(String name, Predicate<Counter> include) {
        return Math.round(meters.find(name).counters().stream()
                .filter(include)
                .mapToDouble(Counter::count)
                .sum());
    }
}

package com.specshield.ledgerline.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.specshield.ledgerline.application.PaymentEventService;
import com.specshield.ledgerline.application.PaymentEventService.Outcome;
import com.specshield.ledgerline.application.PaymentInbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrphanReprocessorTest {

    @Test
    @DisplayName("orphans in the retry window are reprocessed per order; one failing order does not stop the rest")
    void reprocesses() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        Instant since = now.minus(Duration.ofHours(1));
        PaymentInbox inbox = mock(PaymentInbox.class);
        PaymentEventService events = mock(PaymentEventService.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(inbox.orphanOrderIds(since)).thenReturn(List.of(a, b, c));
        when(events.reprocessOrphans(a, since)).thenReturn(new PaymentEventService.Result(Outcome.APPLIED, 2, 0));
        when(events.reprocessOrphans(b, since)).thenThrow(new IllegalStateException("boom"));
        when(events.reprocessOrphans(c, since)).thenReturn(new PaymentEventService.Result(Outcome.STALE, 0, 1));
        OrphanReprocessor reprocessor = new OrphanReprocessor(
                inbox,
                events,
                new WebhookProperties(List.of("z".repeat(40)), null, null),
                new WebhookMetrics(meters),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(reprocessor.run()).isEqualTo(2);
        assertThat(meters.get("ledgerline.webhook.applied").counter().count()).isEqualTo(2);
        assertThat(meters.get("ledgerline.webhook.stale").counter().count()).isEqualTo(1);

        when(inbox.orphanOrderIds(since)).thenReturn(List.of());
        assertThat(reprocessor.run()).isZero();
    }
}

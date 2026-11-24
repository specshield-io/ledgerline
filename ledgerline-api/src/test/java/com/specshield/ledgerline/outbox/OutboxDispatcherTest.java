package com.specshield.ledgerline.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.specshield.ledgerline.application.OutboxMessage;
import com.specshield.ledgerline.application.OutboxStatus;
import com.specshield.ledgerline.application.OutboxStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxDispatcherTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final OutboxStore store = mock(OutboxStore.class);
    final MerchantDeliveryClient client = mock(MerchantDeliveryClient.class);
    final MeterRegistry meters = new SimpleMeterRegistry();
    final TestObservationRegistry observations = TestObservationRegistry.create();
    final OutboxProperties properties = new OutboxProperties(true, null, 20, null, null, 8, null, null);
    final OutboxMetrics metrics = new OutboxMetrics(meters, store, clock);
    final OutboxDispatcher dispatcher =
            new OutboxDispatcher(store, client, policy(), metrics, observations, clock, properties);

    static BackoffPolicy policy() {
        return new BackoffPolicy(Duration.ofSeconds(2), Duration.ofHours(1), 8, new SplittableRandom(5));
    }

    static OutboxMessage message(long id, int attempts) {
        return new OutboxMessage(
                id,
                UUID.fromString("0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b"),
                "order.paid",
                "{}",
                OutboxStatus.IN_FLIGHT,
                attempts,
                NOW,
                null,
                NOW,
                null,
                NOW);
    }

    double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("nothing due: no deliveries")
    void empty() {
        when(store.claimDue(20, NOW)).thenReturn(List.of());

        assertThat(dispatcher.dispatchOnce()).isZero();
        verify(client, never()).deliver(any());
    }

    @Test
    @DisplayName("a 2xx is recorded as delivered under the claim, counted, and observed as outbox.deliver")
    void delivered() {
        when(store.claimDue(20, NOW)).thenReturn(List.of(message(1, 0)));
        when(client.deliver(any())).thenReturn(new DeliveryResult.Delivered(204));
        when(store.markDelivered(1, NOW, NOW)).thenReturn(true);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        verify(store).markDelivered(1, NOW, NOW);
        assertThat(count("ledgerline.outbox.delivered")).isEqualTo(1);
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("outbox.deliver")
                .that()
                .hasLowCardinalityKeyValue("result", "delivered")
                .hasLowCardinalityKeyValue("event.type", "order.paid")
                .hasHighCardinalityKeyValue("outbox.id", "1")
                .hasHighCardinalityKeyValue("attempt", "1");
    }

    @Test
    @DisplayName("a failure is retried after the policy's delay for the next attempt")
    void retry() {
        when(store.claimDue(20, NOW)).thenReturn(List.of(message(2, 2)));
        when(client.deliver(any())).thenReturn(new DeliveryResult.Failed("http_5xx", "HTTP 500"));
        when(store.markRetry(eq(2L), eq(NOW), eq("HTTP 500"), any())).thenReturn(true);
        Duration expected = ((BackoffPolicy.Retry) policy().next(4)).delay();

        dispatcher.dispatchOnce();

        verify(store).markRetry(2, NOW, "HTTP 500", NOW.plus(expected));
        verify(store, never()).markDead(anyInt(), any(), anyString());
        assertThat(count("ledgerline.outbox.failed", "reason", "http_5xx")).isEqualTo(1);
        assertThat(count("ledgerline.outbox.dead")).isZero();
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("outbox.deliver")
                .that()
                .hasLowCardinalityKeyValue("result", "http_5xx");
    }

    @Test
    @DisplayName("the eighth failure is DEAD; an exception from the client counts as an I/O failure")
    void dead() {
        when(store.claimDue(20, NOW)).thenReturn(List.of(message(3, 7)));
        when(client.deliver(any())).thenThrow(new IllegalStateException("boom"));
        when(store.markDead(3, NOW, "delivery failed: IllegalStateException")).thenReturn(true);

        dispatcher.dispatchOnce();

        verify(store).markDead(3, NOW, "delivery failed: IllegalStateException");
        verify(store, never()).markRetry(anyInt(), any(), anyString(), any());
        assertThat(count("ledgerline.outbox.dead")).isEqualTo(1);
        assertThat(count("ledgerline.outbox.failed", "reason", "io")).isEqualTo(1);
    }

    @Test
    @DisplayName("a result for a claim the watchdog released is not counted as dead")
    void lostClaim() {
        when(store.claimDue(20, NOW)).thenReturn(List.of(message(4, 7)));
        when(client.deliver(any())).thenReturn(new DeliveryResult.Failed("timeout", "no response"));
        when(store.markDead(4, NOW, "no response")).thenReturn(false);

        dispatcher.dispatchOnce();

        assertThat(count("ledgerline.outbox.dead")).isZero();
    }

    @Test
    @DisplayName("watchdog releases rows claimed longer ago than stuckAfter; metrics gauges read the store")
    void watchdogAndGauges() {
        OutboxWatchdog watchdog = new OutboxWatchdog(store, metrics, clock, properties);
        when(store.releaseStuck(eq(NOW.minusSeconds(60)), anyString(), eq(NOW))).thenReturn(2, 0);

        assertThat(watchdog.releaseStuck()).isEqualTo(2);
        assertThat(watchdog.releaseStuck()).isZero();
        assertThat(count("ledgerline.outbox.watchdog.released")).isEqualTo(2);

        when(store.countByStatus(OutboxStatus.PENDING)).thenReturn(5L);
        when(store.oldestPendingCreatedAt()).thenReturn(Optional.of(NOW.minusSeconds(90)), Optional.empty());
        assertThat(meters.get("ledgerline.outbox.pending").gauge().value()).isEqualTo(5);
        assertThat(meters.get("ledgerline.outbox.oldest.pending.age").gauge().value())
                .isEqualTo(90);
        assertThat(meters.get("ledgerline.outbox.oldest.pending.age").gauge().value())
                .isZero();

        when(store.countByStatus(OutboxStatus.PENDING)).thenThrow(new IllegalStateException("db down"));
        assertThat(meters.get("ledgerline.outbox.pending").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("properties default to the documented values")
    void defaults() {
        OutboxProperties d = new OutboxProperties(false, null, 0, null, null, 0, null, null);
        assertThat(d.pollInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(d.batchSize()).isEqualTo(20);
        assertThat(d.backoffBase()).isEqualTo(Duration.ofSeconds(2));
        assertThat(d.backoffCap()).isEqualTo(Duration.ofHours(1));
        assertThat(d.maxAttempts()).isEqualTo(8);
        assertThat(d.stuckAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(d.watchdogInterval()).isEqualTo(Duration.ofSeconds(10));
        OutboxProperties set = new OutboxProperties(
                true,
                Duration.ofSeconds(1),
                5,
                Duration.ofSeconds(3),
                Duration.ofMinutes(5),
                4,
                Duration.ofSeconds(30),
                Duration.ofSeconds(2));
        assertThat(set.batchSize()).isEqualTo(5);
        assertThat(set.maxAttempts()).isEqualTo(4);
        assertThat(set.stuckAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(set.pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(set.backoffBase()).isEqualTo(Duration.ofSeconds(3));
        assertThat(set.backoffCap()).isEqualTo(Duration.ofMinutes(5));
        assertThat(set.watchdogInterval()).isEqualTo(Duration.ofSeconds(2));

        MerchantProperties merchant = new MerchantProperties("http://merchant", List.of("s".repeat(32)), null, null);
        assertThat(merchant.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(merchant.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(merchant.toString()).doesNotContain("s".repeat(32)).contains("1 redacted");
    }
}

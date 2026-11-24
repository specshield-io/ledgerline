package com.specshield.ledgerline.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderIds;
import com.specshield.ledgerline.domain.OrderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Reconciliation, outbox administration, demo data and merchant notifications. */
class SmallServicesTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().build();
    final InMemory.Orders orders = new InMemory.Orders();
    final InMemory.Audit audit = new InMemory.Audit();

    Order order(OrderStatus status) {
        return orders.insert(InMemory.order(UUID.randomUUID(), status, NOW));
    }

    @Test
    @DisplayName("reconciliation records each distinct mismatch once, skips consistent and recent payments")
    void reconciliation() {
        ProviderLedger ledger = mock(ProviderLedger.class);
        ReconciliationService service =
                new ReconciliationService(ledger, orders, audit, clock, Duration.ofMinutes(2), json);
        Order paid = order(OrderStatus.PAID);
        Order pending = order(OrderStatus.PAYMENT_PENDING);
        Order failed = order(OrderStatus.PAYMENT_FAILED);
        UUID missing = UUID.randomUUID();
        when(ledger.latestSettledPayments(NOW.minus(Duration.ofMinutes(2))))
                .thenReturn(List.of(
                        new ProviderLedger.SettledPayment("mp_ok", paid.id(), "SUCCEEDED", NOW),
                        new ProviderLedger.SettledPayment("mp_ok2", failed.id(), "FAILED", NOW),
                        new ProviderLedger.SettledPayment("mp_bad", pending.id(), "SUCCEEDED", NOW),
                        new ProviderLedger.SettledPayment("mp_gone", missing, "FAILED", NOW)));

        assertThat(service.reconcile()).isEqualTo(2);
        assertThat(service.reconcile()).as("already recorded").isZero();

        assertThat(audit.records).extracting(AuditRecord::orderId).containsExactly(pending.id(), missing);
        assertThat(json.readTree(audit.records.get(1).detailJson())
                        .get("orderStatus")
                        .asString())
                .isEqualTo("MISSING");
        assertThat(ReconciliationService.consistent("SUCCEEDED", OrderStatus.REFUNDED))
                .isTrue();
        assertThat(ReconciliationService.consistent("SUCCEEDED", OrderStatus.PARTIALLY_REFUNDED))
                .isTrue();
        assertThat(ReconciliationService.consistent("FAILED", OrderStatus.PAID)).isFalse();
        assertThat(ReconciliationService.consistent("PENDING", OrderStatus.CREATED))
                .isTrue();
    }

    @Test
    @DisplayName("replay moves a DEAD message back, audits the operator; anything else is refused")
    void outboxAdmin() {
        OutboxStore store = mock(OutboxStore.class);
        OutboxAdminService service = new OutboxAdminService(store, audit, clock, json);
        UUID aggregate = UUID.randomUUID();
        OutboxMessage dead = new OutboxMessage(
                7, aggregate, "order.paid", "{}", OutboxStatus.DEAD, 8, NOW, "HTTP 500", NOW, null, null);
        OutboxMessage pending = new OutboxMessage(
                7, aggregate, "order.paid", "{}", OutboxStatus.PENDING, 0, NOW, null, NOW, null, null);
        when(store.findByIdForUpdate(7)).thenReturn(Optional.of(dead), Optional.of(pending), Optional.of(pending));

        assertThat(service.replay(7, "ops")).isEqualTo(pending);
        verify(store).requeue(7, NOW);
        assertThat(audit.records).singleElement().satisfies(r -> {
            assertThat(r.actor()).isEqualTo("operator:ops");
            assertThat(r.kind()).isEqualTo("OUTBOX_REPLAYED");
            assertThat(r.orderId()).isEqualTo(aggregate);
            assertThat(json.readTree(r.detailJson()).get("previousAttempts").asInt())
                    .isEqualTo(8);
        });

        assertThatThrownBy(() -> service.replay(7, "ops"))
                .isInstanceOf(OutboxMessageNotReplayableException.class)
                .extracting(e -> ((OutboxMessageNotReplayableException) e).status())
                .isEqualTo(OutboxStatus.PENDING);
        when(store.findByIdForUpdate(8)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replay(8, "ops")).isInstanceOf(OutboxMessageNotFoundException.class);
        verify(store, never()).requeue(8, NOW);

        OutboxStore.OutboxPage page = new OutboxStore.OutboxPage(List.of(dead), 0, 20, 1, 1);
        when(store.page(Optional.empty(), 0, 20)).thenReturn(page);
        assertThat(service.list(Optional.empty(), 0, 20)).isEqualTo(page);
    }

    @Test
    @DisplayName("demo data: 25 orders across every status with consistent sequences and refunds")
    void demoData() {
        DemoDataStore store = mock(DemoDataStore.class);
        DemoDataService service =
                new DemoDataService(store, orders, audit, new OrderIds(clock, new SplittableRandom(3)), clock, json);
        when(store.countOrders()).thenReturn(0L, 25L);

        assertThat(service.seedIfEmpty()).isEqualTo(25);
        assertThat(service.seedIfEmpty()).isZero();
        assertThat(orders.rows.values()).hasSize(25);
        assertThat(orders.rows.values().stream().map(Order::status).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(OrderStatus.values());
        assertThat(orders.rows.values()).allSatisfy(o -> {
            assertThat(o.merchantRef()).startsWith(DemoDataService.MERCHANT_REF_PREFIX);
            assertThat(o.lastEventSequence()).isEqualTo(DemoDataService.sequenceFor(o.status()));
            assertThat(o.createdAt()).isBefore(NOW);
            assertThat(o.updatedAt()).isAfterOrEqualTo(o.createdAt());
        });
        assertThat(audit.kinds()).hasSize(25).containsOnly("DEMO_SEEDED");

        assertThat(service.reset("admin:test")).isEqualTo(25);
        verify(store).truncateDemoTables();
        assertThat(audit.records.getLast().kind()).isEqualTo("DEMO_RESET");
        assertThat(audit.records.getLast().actor()).isEqualTo("admin:test");
        assertThat(DemoDataService.sequenceFor(OrderStatus.CANCELLED)).isZero();
        assertThat(DemoDataService.sequenceFor(OrderStatus.PAYMENT_PENDING)).isEqualTo(1);
        assertThat(DemoDataService.sequenceFor(OrderStatus.REFUNDED)).isEqualTo(3);
    }

    @Test
    @DisplayName("merchant notifications: announced statuses only, with the order's data")
    void notifications() {
        InMemory.OutboxRows rows = new InMemory.OutboxRows();
        MerchantNotifications notifications = new MerchantNotifications(rows, json);
        Order paid = order(OrderStatus.PAID);

        notifications.statusChanged(OrderStatus.PAYMENT_PENDING, paid, NOW);
        notifications.statusChanged(OrderStatus.CREATED, order(OrderStatus.PAYMENT_PENDING), NOW);

        assertThat(rows.rows).singleElement().satisfies(r -> {
            assertThat(r.eventType()).isEqualTo("order.paid");
            assertThat(json.readTree(r.payloadJson()).get("previousStatus").asString())
                    .isEqualTo("PAYMENT_PENDING");
            assertThat(json.readTree(r.payloadJson()).get("amountMinor").asLong())
                    .isEqualTo(2599);
        });
        assertThat(MerchantNotifications.eventType(OrderStatus.CREATED)).isEmpty();
        assertThat(MerchantNotifications.eventType(OrderStatus.REFUNDED)).contains("order.refunded");
        assertThat(MerchantNotifications.eventType(OrderStatus.PARTIALLY_REFUNDED))
                .contains("order.partially_refunded");
        assertThat(MerchantNotifications.eventType(OrderStatus.PAYMENT_FAILED)).contains("order.payment_failed");
        assertThat(MerchantNotifications.eventType(OrderStatus.CANCELLED)).contains("order.cancelled");
        assertThat(new OrderNotFoundException(paid.id()))
                .hasMessageContaining(paid.id().toString());
    }
}

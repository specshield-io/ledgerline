package com.specshield.ledgerline.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.specshield.ledgerline.domain.IllegalTransitionException;
import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderIds;
import com.specshield.ledgerline.domain.OrderStateMachine;
import com.specshield.ledgerline.domain.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderServiceTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().build();
    final InMemory.Orders orders = new InMemory.Orders();
    final InMemory.Audit audit = new InMemory.Audit();
    final InMemory.OutboxRows outboxRows = new InMemory.OutboxRows();
    final OrderQueries queries = mock(OrderQueries.class);
    final PaymentGateway payments = mock(PaymentGateway.class);
    final OutboxStore outboxStore = mock(OutboxStore.class);
    final OrderService service = new OrderService(
            orders,
            queries,
            audit,
            payments,
            new MerchantNotifications(outboxRows, json),
            outboxStore,
            new OrderStateMachine(clock),
            new OrderIds(clock, new SplittableRandom(1)),
            clock,
            json);

    @Test
    @DisplayName("create stores the order and audits ORDER_CREATED with the amount")
    void create() {
        Order order = service.create("synthetic-1", 2599, "USD");

        assertThat(orders.findById(order.id())).contains(order);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(audit.records).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("ORDER_CREATED");
            assertThat(r.actor()).isEqualTo("api");
            assertThat(r.createdAt()).isEqualTo(NOW);
            assertThat(json.readTree(r.detailJson()).get("amountMinor").asLong())
                    .isEqualTo(2599);
            assertThat(json.readTree(r.detailJson()).get("status").asString()).isEqualTo("CREATED");
        });
    }

    @Test
    @DisplayName("get, deliveries and timeline of an unknown order throw OrderNotFoundException")
    void notFound() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.deliveries(id)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.timeline(id)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.pay(id)).isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("pay asks the gateway and audits PAYMENT_REQUESTED; a paid order cannot be paid again")
    void pay() {
        Order order = service.create("synthetic-1", 2599, "USD");
        when(payments.startPayment(any())).thenReturn("mp_123");

        OrderService.PaymentRequested requested = service.pay(order.id());

        assertThat(requested.paymentReference()).isEqualTo("mp_123");
        assertThat(audit.kinds()).containsExactly("ORDER_CREATED", "PAYMENT_REQUESTED");
        assertThat(json.readTree(audit.records.getLast().detailJson())
                        .get("paymentReference")
                        .asString())
                .isEqualTo("mp_123");

        orders.update(InMemory.order(order.id(), OrderStatus.PAID, NOW));
        assertThatThrownBy(() -> service.pay(order.id())).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    @DisplayName("cancel updates the order, audits ORDER_CANCELLED and queues order.cancelled in the same call")
    void cancel() {
        Order order = service.create("synthetic-1", 2599, "USD");

        Order cancelled = service.cancel(order.id());

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(audit.kinds()).containsExactly("ORDER_CREATED", "ORDER_CANCELLED");
        assertThat(json.readTree(audit.records.getLast().detailJson())
                        .get("from")
                        .asString())
                .isEqualTo("CREATED");
        assertThat(outboxRows.rows)
                .singleElement()
                .extracting(InMemory.OutboxRow::eventType)
                .isEqualTo("order.cancelled");
    }

    @Test
    @DisplayName("the timeline merges audit and inbox entries by time; deliveries come from the outbox")
    void timelineAndDeliveries() {
        Order order = service.create("synthetic-1", 2599, "USD");
        when(queries.inboxForOrder(order.id()))
                .thenReturn(List.of(
                        new InboxRecord(1L, "mockpay", "evt_1", "{}", true, NOW.minusSeconds(5), NOW, "APPLIED")));
        when(outboxStore.forAggregate(order.id())).thenReturn(List.of());

        List<TimelineEntry> timeline = service.timeline(order.id());

        assertThat(timeline)
                .extracting(TimelineEntry::source)
                .containsExactly(TimelineEntry.Source.INBOX, TimelineEntry.Source.AUDIT);
        assertThat(timeline.getFirst().providerEventId()).isEqualTo("evt_1");
        assertThat(timeline.getFirst().outcome()).isEqualTo("APPLIED");
        assertThat(timeline.getLast().kind()).isEqualTo("ORDER_CREATED");
        assertThat(service.deliveries(order.id())).isEmpty();
        verify(outboxStore).forAggregate(order.id());

        when(queries.page(Optional.of(OrderStatus.PAID), 0, 20))
                .thenReturn(new OrderQueries.OrderPage(List.of(order), 0, 20, 41));
        assertThat(service.list(Optional.of(OrderStatus.PAID), 0, 20).totalPages())
                .isEqualTo(3);
    }
}

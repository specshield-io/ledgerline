package com.specshield.ledgerline.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderEventSequencer;
import com.specshield.ledgerline.domain.OrderStateMachine;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.webhook.MockPayEventDecoder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The inbox flow with in-memory ports and the real sequencer, state machine and decoder. */
class PaymentEventServiceTest {

    static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final JsonMapper json = JsonMapper.builder().build();
    final MockPayEventDecoder decoder = new MockPayEventDecoder(json);
    final InMemory.Inbox inbox = new InMemory.Inbox();
    final InMemory.Orders orders = new InMemory.Orders();
    final InMemory.Audit audit = new InMemory.Audit();
    final InMemory.OutboxRows outbox = new InMemory.OutboxRows();
    final List<Order> beforeCommit = new ArrayList<>();
    PaymentEventService service;
    Order order;

    @BeforeEach
    void setUp() {
        service = new PaymentEventService(
                inbox,
                orders,
                audit,
                new MerchantNotifications(outbox, json),
                new OrderEventSequencer(new OrderStateMachine(clock)),
                decoder,
                beforeCommit::add,
                clock,
                json);
        order = orders.insert(
                Order.create(UUID.fromString("0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b"), "synthetic-1", 2599, "USD", NOW));
    }

    String payload(String id, String type, long sequence, UUID orderId) {
        return """
                {"id":"%s","type":"%s","created":%d,"sequence":%d,"data":{"orderId":"%s","amountMinor":2599,"currency":"USD"}}""".formatted(id, type, NOW.getEpochSecond(), sequence, orderId);
    }

    PaymentEventService.Result deliver(String id, String type, long sequence) {
        return deliver(id, type, sequence, order.id());
    }

    PaymentEventService.Result deliver(String id, String type, long sequence, UUID orderId) {
        String body = payload(id, type, sequence, orderId);
        return service.process(decoder.decode(body), body);
    }

    @Test
    @DisplayName("an applicable event changes the order, marks the inbox APPLIED, audits it and queues order.paid")
    void applies() {
        assertThat(deliver("evt_1", "PAYMENT_INITIATED", 1).outcome()).isEqualTo(PaymentEventService.Outcome.APPLIED);
        PaymentEventService.Result paid = deliver("evt_2", "PAYMENT_SUCCEEDED", 2);

        assertThat(paid).isEqualTo(new PaymentEventService.Result(PaymentEventService.Outcome.APPLIED, 1, 0));
        assertThat(orders.findById(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(inbox.outcome("evt_2")).isEqualTo("APPLIED");
        assertThat(audit.kinds()).containsExactly("PAYMENT_EVENT_APPLIED", "PAYMENT_EVENT_APPLIED");
        AuditRecord last = audit.records.getLast();
        assertThat(last.actor()).isEqualTo("provider:mockpay");
        assertThat(last.orderId()).isEqualTo(order.id());
        assertThat(json.readTree(last.detailJson()).get("to").asString()).isEqualTo("PAID");
        assertThat(json.readTree(last.detailJson()).get("from").asString()).isEqualTo("PAYMENT_PENDING");
        assertThat(json.readTree(last.detailJson()).get("sequence").asLong()).isEqualTo(2);
        assertThat(outbox.rows).singleElement().satisfies(r -> {
            assertThat(r.eventType()).isEqualTo("order.paid");
            assertThat(r.aggregateId()).isEqualTo(order.id());
        });
        assertThat(orders.updates).isEqualTo(2);
        assertThat(beforeCommit).hasSize(2).last().extracting(Order::status).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("a repeated event id is a DUPLICATE: audited, nothing else changes")
    void duplicate() {
        deliver("evt_1", "PAYMENT_INITIATED", 1);
        int updates = orders.updates;

        PaymentEventService.Result again = deliver("evt_1", "PAYMENT_INITIATED", 1);

        assertThat(again).isEqualTo(new PaymentEventService.Result(PaymentEventService.Outcome.DUPLICATE, 0, 0));
        assertThat(orders.updates).isEqualTo(updates);
        assertThat(audit.kinds()).containsExactly("PAYMENT_EVENT_APPLIED", "DUPLICATE");
        assertThat(json.readTree(audit.records.getLast().detailJson())
                        .get("providerEventId")
                        .asString())
                .isEqualTo("evt_1");
    }

    @Test
    @DisplayName("an unknown event type is IGNORED and an unknown order is ORPHAN; neither touches orders")
    void ignoredAndOrphan() {
        PaymentEventService.Result ignored = deliver("evt_x", "PAYMENT_DISPUTED", 1);
        PaymentEventService.Result orphan = deliver("evt_o", "PAYMENT_INITIATED", 1, UUID.randomUUID());

        assertThat(ignored.outcome()).isEqualTo(PaymentEventService.Outcome.IGNORED);
        assertThat(orphan.outcome()).isEqualTo(PaymentEventService.Outcome.ORPHAN);
        assertThat(inbox.outcome("evt_x")).isEqualTo("IGNORED");
        assertThat(inbox.outcome("evt_o")).isEqualTo("ORPHAN");
        assertThat(audit.kinds()).containsExactly("EVENT_IGNORED", "EVENT_ORPHAN");
        assertThat(orders.updates).isZero();
        assertThat(beforeCommit).isEmpty();
    }

    @Test
    @DisplayName("an event after a gap waits (PENDING); the missing one applies both, in sequence order")
    void gap() {
        PaymentEventService.Result early = deliver("evt_2", "PAYMENT_SUCCEEDED", 2);
        assertThat(early).isEqualTo(new PaymentEventService.Result(PaymentEventService.Outcome.PENDING, 0, 0));
        assertThat(inbox.outcome("evt_2")).isNull();
        assertThat(orders.updates).isZero();

        PaymentEventService.Result missing = deliver("evt_1", "PAYMENT_INITIATED", 1);

        assertThat(missing).isEqualTo(new PaymentEventService.Result(PaymentEventService.Outcome.APPLIED, 2, 0));
        assertThat(orders.findById(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(inbox.outcome("evt_2")).isEqualTo("APPLIED");
        assertThat(orders.updates).isEqualTo(1);
    }

    @Test
    @DisplayName("a late event at or below the applied sequence is STALE and counted")
    void stale() {
        deliver("evt_1", "PAYMENT_INITIATED", 1);
        deliver("evt_2", "PAYMENT_SUCCEEDED", 2);

        PaymentEventService.Result late = deliver("evt_late", "PAYMENT_FAILED", 1);

        assertThat(late).isEqualTo(new PaymentEventService.Result(PaymentEventService.Outcome.STALE, 0, 1));
        assertThat(inbox.outcome("evt_late")).isEqualTo("STALE");
        assertThat(audit.kinds()).last().isEqualTo("PAYMENT_EVENT_STALE");
        assertThat(json.readTree(audit.records.getLast().detailJson()).has("reason"))
                .isTrue();
        assertThat(orders.findById(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("an event the state machine refuses is FAILED and does not change the order")
    void failed() {
        PaymentEventService.Result refused = deliver("evt_r", "REFUND_SUCCEEDED", 1);

        assertThat(refused.outcome()).isEqualTo(PaymentEventService.Outcome.FAILED);
        assertThat(inbox.outcome("evt_r")).isEqualTo("FAILED");
        assertThat(audit.kinds()).containsExactly("PAYMENT_EVENT_FAILED");
        assertThat(orders.updates).isZero();
        assertThat(outbox.rows).isEmpty();
    }

    @Test
    @DisplayName("orphans are reprocessed once their order exists; nothing happens when none were reopened")
    void orphans() {
        UUID later = UUID.fromString("0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2c");
        deliver("evt_o1", "PAYMENT_INITIATED", 1, later);
        assertThat(service.reprocessOrphans(later, NOW.minusSeconds(60)).outcome())
                .as("order still missing")
                .isEqualTo(PaymentEventService.Outcome.ORPHAN);

        orders.insert(Order.create(later, "synthetic-2", 999, "USD", NOW));
        PaymentEventService.Result result = service.reprocessOrphans(later, NOW.minusSeconds(60));

        assertThat(result).isEqualTo(new PaymentEventService.Result(PaymentEventService.Outcome.APPLIED, 1, 0));
        assertThat(inbox.outcome("evt_o1")).isEqualTo("APPLIED");
        assertThat(service.reprocessOrphans(later, NOW.minusSeconds(60)).outcome())
                .as("nothing left to reopen")
                .isEqualTo(PaymentEventService.Outcome.ORPHAN);
    }

    @Test
    @DisplayName("a rejected delivery stores only a hash and length under a synthetic id, and audits the reason")
    void reject() {
        service.reject("no_matching_signature", "{\"id\":\"evt_forged\"}".getBytes());

        InMemory.Inbox.Row row = inbox.rows.getFirst();
        assertThat(row.eventId()).startsWith("rejected_");
        assertThat(row.valid()).isFalse();
        assertThat(row.payload()).doesNotContain("evt_forged");
        assertThat(json.readTree(row.payload()).get("bodyLength").asInt()).isEqualTo(19);
        assertThat(json.readTree(row.payload()).get("bodySha256").asString()).hasSize(64);
        assertThat(audit.records).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("SECURITY_REJECTED");
            assertThat(r.orderId()).isNull();
            assertThat(json.readTree(r.detailJson()).get("reason").asString()).isEqualTo("no_matching_signature");
        });
        assertThat(PaymentEventService.sha256(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}

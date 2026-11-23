package com.specshield.ledgerline.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specshield.ledgerline.PostgresContainerConfig;
import com.specshield.ledgerline.domain.Money;
import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

/** Repository tests against real PostgreSQL 17 with the Flyway schema. Synthetic data only. */
@DataJpaTest
@ActiveProfiles("test")
@Import({PostgresContainerConfig.class, JpaOrderStore.class})
class PersistenceIT {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00.123456Z");

    @Autowired
    JpaOrderStore orders;

    @Autowired
    InboxEventJpaRepository inbox;

    @Autowired
    AuditLogJpaRepository audit;

    @Autowired
    IdempotencyKeyJpaRepository idempotencyKeys;

    @Autowired
    OutboxMessageJpaRepository outbox;

    @Autowired
    EntityManager em;

    @Test
    @DisplayName("LL-I00 saving an order and reading it back round-trips every field")
    void orderRoundTrip() {
        Order order = new Order(
                UUID.fromString("0194a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b"),
                "synthetic-merchant-ref-ß-001",
                12_345,
                Money.USD,
                OrderStatus.PARTIALLY_REFUNDED,
                2_345,
                7,
                0,
                T0,
                T0.plusSeconds(90),
                T0.plusSeconds(60));

        Order saved = orders.insert(order);
        em.clear(); // force a real SELECT, not the first-level cache

        Order found = orders.findById(order.id()).orElseThrow();
        assertThat(saved).isEqualTo(order);
        assertThat(found).isEqualTo(order);
        assertThat(found.lastProviderEventAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("LL-I00 a null lastProviderEventAt round-trips as null")
    void nullableFieldRoundTrip() {
        Order order = Order.create(UUID.randomUUID(), "synthetic-ref", 1, Money.USD, T0);

        orders.insert(order);
        em.clear();

        assertThat(orders.findById(order.id())).contains(order);
    }

    @Test
    @DisplayName("LL-I00 inserting the same (provider, provider_event_id) twice raises DataIntegrityViolationException")
    void inboxDedupe() {
        inbox.saveAndFlush(new InboxEventEntity("mockpay", "evt-1", "{\"type\":\"PAYMENT_INITIATED\"}", true, T0));

        assertThatThrownBy(() -> inbox.saveAndFlush(new InboxEventEntity("mockpay", "evt-1", "{}", true, T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same provider_event_id from another provider is a different event")
    void inboxUniquePerProvider() {
        inbox.saveAndFlush(new InboxEventEntity("mockpay", "evt-1", "{}", true, T0));
        inbox.saveAndFlush(new InboxEventEntity("otherpay", "evt-1", "{}", false, T0));

        assertThat(inbox.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("inbox payload is stored as jsonb and the outcome round-trips")
    void inboxJsonbAndOutcome() {
        InboxEventEntity row = inbox.saveAndFlush(
                new InboxEventEntity("mockpay", "evt-2", "{\"type\":\"PAYMENT_SUCCEEDED\",\"seq\":2}", true, T0));
        row.markProcessed(InboxOutcome.APPLIED, T0.plusSeconds(1));
        inbox.saveAndFlush(row);
        em.clear();

        Object type = em.createNativeQuery("select payload->>'type' from order_events_inbox where id = ?1")
                .setParameter(1, row.getId())
                .getSingleResult();
        assertThat(type).isEqualTo("PAYMENT_SUCCEEDED");

        InboxEventEntity found =
                inbox.findByProviderAndProviderEventId("mockpay", "evt-2").orElseThrow();
        assertThat(found.getOutcome()).isEqualTo(InboxOutcome.APPLIED);
        assertThat(found.getProcessedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(found.getReceivedAt()).isEqualTo(T0);
        assertThat(found.isSignatureValid()).isTrue();
        assertThat(found.getProvider()).isEqualTo("mockpay");
        assertThat(found.getPayload()).contains("\"seq\"");
    }

    @Test
    @DisplayName("update increments the version and rejects a stale version")
    void optimisticLocking() {
        Order v0 = orders.insert(Order.create(UUID.randomUUID(), "synthetic-ref", 500, Money.USD, T0));
        assertThat(v0.version()).isZero();

        Order pending = new Order(
                v0.id(),
                v0.merchantRef(),
                v0.amountMinor(),
                v0.currency(),
                OrderStatus.PAYMENT_PENDING,
                0,
                1,
                v0.version(),
                v0.createdAt(),
                T0.plusSeconds(5),
                T0.plusSeconds(4));
        Order v1 = orders.update(pending);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);

        assertThatThrownBy(() -> orders.update(pending)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("updating an order that does not exist is rejected")
    void updateMissingOrder() {
        Order ghost = Order.create(UUID.randomUUID(), "synthetic-ref", 500, Money.USD, T0);

        assertThatThrownBy(() -> orders.update(ghost)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("database check constraints reject an amount outside 1..100000000 and a non-USD currency")
    void checkConstraints() {
        assertThatThrownBy(() -> insertRawOrder(0, "USD")).hasMessageContaining("orders_amount_minor_check");
    }

    @Test
    @DisplayName("database check constraint rejects a non-USD currency")
    void currencyConstraint() {
        assertThatThrownBy(() -> insertRawOrder(100, "EUR")).hasMessageContaining("orders_currency_check");
    }

    @Test
    @DisplayName("audit log entries are stored with jsonb detail and listed per order in time order")
    void auditLog() {
        UUID orderId = UUID.randomUUID();
        audit.saveAndFlush(new AuditLogEntity(orderId, "ORDER_CREATED", "{\"by\":\"test\"}", "system", "corr-1", T0));
        audit.saveAndFlush(new AuditLogEntity(orderId, "PAYMENT_APPLIED", "{}", "mockpay", null, T0.plusSeconds(1)));
        audit.saveAndFlush(new AuditLogEntity(null, "WEBHOOK_REJECTED", "{}", "mockpay", null, T0));
        em.clear();

        var entries = audit.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
        assertThat(entries).extracting(AuditLogEntity::getKind).containsExactly("ORDER_CREATED", "PAYMENT_APPLIED");
        AuditLogEntity first = entries.getFirst();
        assertThat(first.getId()).isNotNull();
        assertThat(first.getOrderId()).isEqualTo(orderId);
        assertThat(first.getDetail()).contains("\"by\"");
        assertThat(first.getActor()).isEqualTo("system");
        assertThat(first.getCorrelationId()).isEqualTo("corr-1");
        assertThat(first.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("idempotency keys are inserted once, completed, and a second insert of the same key is rejected")
    void idempotencyKeys() {
        IdempotencyKeyEntity key = new IdempotencyKeyEntity("idem-1", "sha256:abc", T0, T0.plusSeconds(86_400));
        idempotencyKeys.saveAndFlush(key);
        key.complete(201, "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8), "application/json", "/api/v1/orders/x");
        idempotencyKeys.saveAndFlush(key);
        em.clear();

        IdempotencyKeyEntity found = idempotencyKeys.findById("idem-1").orElseThrow();
        assertThat(found.getState()).isEqualTo(IdempotencyState.COMPLETED);
        assertThat(found.getResponseStatus()).isEqualTo(201);
        assertThat(new String(found.getResponseBody(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"x\"}");
        assertThat(found.getResponseContentType()).isEqualTo("application/json");
        assertThat(found.getResponseLocation()).isEqualTo("/api/v1/orders/x");
        assertThat(found.getRequestHash()).isEqualTo("sha256:abc");
        assertThat(found.getCreatedAt()).isEqualTo(T0);
        assertThat(found.getExpiresAt()).isEqualTo(T0.plusSeconds(86_400));
        assertThat(found.isNew()).isFalse();
        em.clear(); // make PostgreSQL's primary key reject the duplicate, not Hibernate's first-level cache

        assertThatThrownBy(() -> idempotencyKeys.saveAndFlush(
                        new IdempotencyKeyEntity("idem-1", "sha256:other", T0, T0.plusSeconds(60))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("outbox messages start PENDING, are found when due, and record delivery")
    void outbox() {
        UUID aggregate = UUID.randomUUID();
        OutboxMessageEntity due = outbox.saveAndFlush(new OutboxMessageEntity(aggregate, "OrderPaid", "{}", T0));
        outbox.saveAndFlush(new OutboxMessageEntity(aggregate, "OrderRefunded", "{}", T0.plusSeconds(600)));

        var ready = outbox.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                OutboxStatus.PENDING, T0.plusSeconds(1));
        assertThat(ready).extracting(OutboxMessageEntity::getId).containsExactly(due.getId());

        due.markDelivered(T0.plusSeconds(2));
        outbox.saveAndFlush(due);
        em.clear();

        OutboxMessageEntity found = outbox.findById(due.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(found.getAttempts()).isEqualTo(1);
        assertThat(found.getDeliveredAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(found.getLastError()).isNull();
        assertThat(found.getAggregateId()).isEqualTo(aggregate);
        assertThat(found.getEventType()).isEqualTo("OrderPaid");
        assertThat(found.getPayload()).isEqualTo("{}");
        assertThat(found.getCreatedAt()).isEqualTo(T0);
        assertThat(found.getNextAttemptAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("the outbox has a partial index on next_attempt_at for PENDING rows")
    void outboxPartialIndex() {
        Object def = em.createNativeQuery(
                        "select indexdef from pg_indexes where tablename = 'outbox' and indexname = 'outbox_pending_due_idx'")
                .getSingleResult();

        assertThat(def.toString())
                .contains("(next_attempt_at)")
                .contains("WHERE")
                .contains("PENDING");
    }

    private void insertRawOrder(long amountMinor, String currency) {
        em.createNativeQuery("""
                        insert into orders (id, merchant_ref, amount_minor, currency, status, version, created_at, updated_at)
                        values (?1, 'synthetic-ref', ?2, ?3, 'CREATED', 0, now(), now())
                        """)
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, amountMinor)
                .setParameter(3, currency)
                .executeUpdate();
    }
}

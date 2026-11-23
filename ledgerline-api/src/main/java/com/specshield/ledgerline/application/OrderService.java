package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderIds;
import com.specshield.ledgerline.domain.OrderStateMachine;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.domain.OrderStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Order use cases. Every state-changing command writes an audit row in the same transaction. */
@Service
public class OrderService {

    /** MDC key holding the request's correlation id (set by the web layer). */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    static final String ACTOR = "api";

    private final OrderStore orders;
    private final OrderQueries queries;
    private final AuditTrail audit;
    private final PaymentGateway payments;
    private final OrderStateMachine stateMachine;
    private final OrderIds ids;
    private final Clock clock;
    private final JsonMapper json;

    public OrderService(
            OrderStore orders,
            OrderQueries queries,
            AuditTrail audit,
            PaymentGateway payments,
            OrderStateMachine stateMachine,
            OrderIds ids,
            Clock clock,
            JsonMapper json) {
        this.orders = orders;
        this.queries = queries;
        this.audit = audit;
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    public record PaymentRequested(Order order, String paymentReference) {}

    @Transactional
    public Order create(String merchantRef, long amountMinor, String currency) {
        Order order = orders.insert(Order.create(ids.next(), merchantRef, amountMinor, currency, clock.instant()));
        audit(order, "ORDER_CREATED", Map.of("amountMinor", order.amountMinor(), "currency", order.currency()));
        return order;
    }

    @Transactional(readOnly = true)
    public Order get(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public OrderQueries.OrderPage list(Optional<OrderStatus> status, int page, int size) {
        return queries.page(status, page, size);
    }

    @Transactional
    public PaymentRequested pay(UUID id) {
        Order order = get(id);
        stateMachine.requirePayable(order);
        String reference = payments.requestPayment(order);
        audit(order, "PAYMENT_REQUESTED", Map.of("paymentReference", reference));
        return new PaymentRequested(order, reference);
    }

    @Transactional
    public Order cancel(UUID id) {
        Order order = get(id);
        Order cancelled = orders.update(stateMachine.cancel(order));
        audit(cancelled, "ORDER_CANCELLED", Map.of("from", order.status().name()));
        return cancelled;
    }

    /** Audit entries and inbox entries for the order, merged by time (oldest first). */
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID id) {
        get(id);
        List<TimelineEntry> entries = new ArrayList<>();
        for (AuditRecord a : audit.forOrder(id)) {
            entries.add(new TimelineEntry(
                    a.createdAt(),
                    TimelineEntry.Source.AUDIT,
                    a.kind(),
                    a.detailJson(),
                    a.actor(),
                    a.correlationId(),
                    null,
                    null));
        }
        for (InboxRecord i : queries.inboxForOrder(id)) {
            entries.add(new TimelineEntry(
                    i.receivedAt(),
                    TimelineEntry.Source.INBOX,
                    "PROVIDER_EVENT",
                    i.payloadJson(),
                    i.provider(),
                    null,
                    i.providerEventId(),
                    i.outcome()));
        }
        entries.sort(Comparator.comparing(TimelineEntry::at).thenComparing(TimelineEntry::source));
        return entries;
    }

    private void audit(Order order, String kind, Map<String, Object> detail) {
        Map<String, Object> full = new LinkedHashMap<>(detail);
        full.put("status", order.status().name());
        audit.append(new AuditRecord(
                null,
                order.id(),
                kind,
                json.writeValueAsString(full),
                ACTOR,
                MDC.get(CORRELATION_ID_MDC_KEY),
                clock.instant()));
    }
}

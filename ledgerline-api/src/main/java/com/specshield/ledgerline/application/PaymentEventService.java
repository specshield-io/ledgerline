package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderEventSequencer;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.domain.OrderStore;
import com.specshield.ledgerline.domain.PaymentEvent;
import com.specshield.ledgerline.domain.Transition;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processes verified provider events through the inbox, in one transaction per delivery:
 *
 * <ol>
 *   <li>store the event in the inbox; if (provider, event id) exists it is a DUPLICATE and nothing else happens;
 *   <li>unknown type: IGNORED; unknown order: ORPHAN (retried by the orphan reprocessor);
 *   <li>otherwise lock the order (SELECT ... FOR UPDATE) and apply all of its pending events in sequence order
 *       through {@link OrderEventSequencer}; events after a gap stay pending until the missing one arrives;
 *   <li>persist the order, every event's outcome, an audit row per event and a merchant notification in the
 *       outbox for each applied change that merchants are told about ({@link MerchantNotifications}).
 * </ol>
 *
 * <p>Any exception rolls the whole delivery back, including the inbox row, so the provider's retry is processed
 * as new.
 */
@Service
public class PaymentEventService {

    static final String ACTOR_PREFIX = "provider:";

    private final PaymentInbox inbox;
    private final OrderStore orders;
    private final AuditTrail audit;
    private final MerchantNotifications notifications;
    private final OrderEventSequencer sequencer;
    private final ProviderEventDecoder decoder;
    private final PaymentEventHook hook;
    private final Clock clock;
    private final JsonMapper json;

    public PaymentEventService(
            PaymentInbox inbox,
            OrderStore orders,
            AuditTrail audit,
            MerchantNotifications notifications,
            OrderEventSequencer sequencer,
            ProviderEventDecoder decoder,
            PaymentEventHook hook,
            Clock clock,
            JsonMapper json) {
        this.inbox = inbox;
        this.orders = orders;
        this.audit = audit;
        this.notifications = notifications;
        this.sequencer = sequencer;
        this.decoder = decoder;
        this.hook = hook;
        this.clock = clock;
        this.json = json;
    }

    public enum Outcome {
        APPLIED,
        DUPLICATE,
        STALE,
        IGNORED,
        ORPHAN,
        FAILED,
        /** Stored, waiting behind a missing earlier event. */
        PENDING
    }

    /**
     * @param outcome what happened to the delivered event
     * @param applied events applied in this delivery (more than one when it closed a gap)
     * @param stale events found stale in this delivery
     */
    public record Result(Outcome outcome, int applied, int stale) {}

    @Transactional
    public Result process(DecodedEvent event, String payloadJson) {
        Instant now = clock.instant();
        Optional<Long> inserted =
                inbox.insertIfAbsent(decoder.provider(), event.eventId(), event.orderId(), payloadJson, now);
        if (inserted.isEmpty()) {
            audit(event.orderId(), "DUPLICATE", detail(event), now);
            return new Result(Outcome.DUPLICATE, 0, 0);
        }
        long inboxId = inserted.get();
        if (!event.knownType()) {
            inbox.markProcessed(inboxId, Outcome.IGNORED.name(), now);
            audit(event.orderId(), "EVENT_IGNORED", detail(event), now);
            return new Result(Outcome.IGNORED, 0, 0);
        }
        Optional<Order> order = orders.findByIdForUpdate(event.orderId());
        if (order.isEmpty()) {
            inbox.markProcessed(inboxId, Outcome.ORPHAN.name(), now);
            audit(event.orderId(), "EVENT_ORPHAN", detail(event), now);
            return new Result(Outcome.ORPHAN, 0, 0);
        }
        return applyPending(order.get(), event.eventId(), now);
    }

    /** Re-processes the order's ORPHAN events received at or after {@code since}, if the order exists now. */
    @Transactional
    public Result reprocessOrphans(UUID orderId, Instant since) {
        Instant now = clock.instant();
        Optional<Order> order = orders.findByIdForUpdate(orderId);
        if (order.isEmpty() || inbox.reopenOrphans(orderId, since) == 0) {
            return new Result(Outcome.ORPHAN, 0, 0);
        }
        return applyPending(order.get(), null, now);
    }

    /** Records a delivery whose signature failed. Never touches the order. */
    @Transactional
    public void reject(String reason, byte[] body) {
        Instant now = clock.instant();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        detail.put("bodySha256", sha256(body));
        detail.put("bodyLength", body.length);
        String detailJson = json.writeValueAsString(detail);
        inbox.insertRejected(decoder.provider(), "rejected_" + UUID.randomUUID(), detailJson, now);
        audit(null, "SECURITY_REJECTED", detail, now);
    }

    private Result applyPending(Order order, String currentEventId, Instant now) {
        List<PaymentInbox.PendingEvent> pending = inbox.pendingForOrder(order.id());
        Map<String, Long> inboxIds = new LinkedHashMap<>();
        List<PaymentEvent> events = new ArrayList<>();
        for (PaymentInbox.PendingEvent p : pending) {
            inboxIds.put(p.providerEventId(), p.id());
            events.add(decoder.decode(p.payloadJson()).event());
        }

        OrderEventSequencer.Result result = sequencer.drain(order, events);
        Outcome current = Outcome.PENDING;
        int applied = 0;
        int stale = 0;
        OrderStatus before = order.status();
        for (OrderEventSequencer.Step step : result.steps()) {
            PaymentEvent e = step.event();
            Outcome outcome = outcomeOf(step);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("providerEventId", e.providerEventId());
            detail.put("type", e.type().name());
            detail.put("sequence", e.sequence());
            detail.put("from", before.name());
            switch (step.transition()) {
                case Transition.Applied a -> {
                    detail.put("to", a.newStatus().name());
                    notifications.statusChanged(before, a.order(), now);
                    before = a.newStatus();
                    applied++;
                }
                case Transition.Stale s -> {
                    detail.put("reason", s.reason());
                    stale++;
                }
                case Transition.Ignored i -> detail.put("reason", i.reason());
                case null -> detail.put("reason", step.failure().getMessage());
            }
            inbox.markProcessed(inboxIds.get(e.providerEventId()), outcome.name(), now);
            audit(order.id(), "PAYMENT_EVENT_" + outcome.name(), detail, now);
            if (e.providerEventId().equals(currentEventId)) {
                current = outcome;
            }
        }
        if (currentEventId == null && applied > 0) {
            current = Outcome.APPLIED;
        }

        if (!result.order().equals(order)) {
            Order saved = orders.update(result.order());
            hook.beforeCommit(saved);
        }
        return new Result(current, applied, stale);
    }

    private static Outcome outcomeOf(OrderEventSequencer.Step step) {
        if (step.failure() != null) {
            return Outcome.FAILED;
        }
        return switch (step.transition()) {
            case Transition.Applied a -> Outcome.APPLIED;
            case Transition.Stale s -> Outcome.STALE;
            case Transition.Ignored i -> Outcome.IGNORED;
        };
    }

    private Map<String, Object> detail(DecodedEvent event) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("providerEventId", event.eventId());
        detail.put("type", event.type());
        return detail;
    }

    private void audit(UUID orderId, String kind, Map<String, Object> detail, Instant now) {
        audit.append(new AuditRecord(
                null,
                orderId,
                kind,
                json.writeValueAsString(detail),
                ACTOR_PREFIX + decoder.provider(),
                MDC.get(OrderService.CORRELATION_ID_MDC_KEY),
                now));
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}

package com.specshield.ledgerline.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Operator actions on the outbox. Every change writes an audit row naming the operator, in the same transaction. */
@Service
public class OutboxAdminService {

    static final String ACTOR_PREFIX = "operator:";

    private final OutboxStore store;
    private final AuditTrail audit;
    private final Clock clock;
    private final JsonMapper json;

    public OutboxAdminService(OutboxStore store, AuditTrail audit, Clock clock, JsonMapper json) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public OutboxStore.OutboxPage list(Optional<OutboxStatus> status, int page, int size) {
        return store.page(status, page, size);
    }

    /**
     * Puts a DEAD message back to PENDING with its attempts reset, so the dispatcher delivers it again.
     *
     * @param operator who asked; recorded as the audit actor {@code operator:<name>}
     */
    @Transactional
    public OutboxMessage replay(long id, String operator) {
        OutboxMessage message = store.findByIdForUpdate(id).orElseThrow(() -> new OutboxMessageNotFoundException(id));
        if (message.status() != OutboxStatus.DEAD) {
            throw new OutboxMessageNotReplayableException(id, message.status());
        }
        Instant now = clock.instant();
        store.requeue(id, now);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outboxId", id);
        detail.put("eventType", message.eventType());
        detail.put("previousAttempts", message.attempts());
        detail.put("lastError", message.lastError());
        audit.append(new AuditRecord(
                null,
                message.aggregateId(),
                "OUTBOX_REPLAYED",
                json.writeValueAsString(detail),
                ACTOR_PREFIX + operator,
                MDC.get(OrderService.CORRELATION_ID_MDC_KEY),
                now));
        return store.findByIdForUpdate(id).orElseThrow();
    }
}

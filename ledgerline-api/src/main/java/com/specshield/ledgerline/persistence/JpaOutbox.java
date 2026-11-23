package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.application.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Joins the caller's transaction, so the outbox row commits atomically with the state change. */
@Repository
public class JpaOutbox implements Outbox {

    private final OutboxMessageJpaRepository repository;

    JpaOutbox(OutboxMessageJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(UUID aggregateId, String eventType, String payloadJson, Instant now) {
        repository.save(new OutboxMessageEntity(aggregateId, eventType, payloadJson, now));
    }
}

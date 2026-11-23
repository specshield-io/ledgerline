package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.application.AuditRecord;
import com.specshield.ledgerline.application.AuditTrail;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Joins the caller's transaction, so the audit row commits or rolls back with the state change. */
@Repository
public class JpaAuditTrail implements AuditTrail {

    private final AuditLogJpaRepository repository;

    JpaAuditTrail(AuditLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(AuditRecord r) {
        repository.save(
                new AuditLogEntity(r.orderId(), r.kind(), r.detailJson(), r.actor(), r.correlationId(), r.createdAt()));
    }

    @Override
    public List<AuditRecord> forOrder(UUID orderId) {
        return repository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .map(e -> new AuditRecord(
                        e.getId(),
                        e.getOrderId(),
                        e.getKind(),
                        e.getDetail(),
                        e.getActor(),
                        e.getCorrelationId(),
                        e.getCreatedAt()))
                .toList();
    }
}

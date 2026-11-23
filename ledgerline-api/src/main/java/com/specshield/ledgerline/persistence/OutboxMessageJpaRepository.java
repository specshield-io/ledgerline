package com.specshield.ledgerline.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessageEntity, Long> {

    /** Due PENDING messages, oldest first. Served by the partial index outbox_pending_due_idx. */
    List<OutboxMessageEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            OutboxStatus status, Instant now);
}

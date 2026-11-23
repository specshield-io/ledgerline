package com.specshield.ledgerline.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}

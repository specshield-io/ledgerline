package com.specshield.ledgerline.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, Long> {

    Optional<InboxEventEntity> findByProviderAndProviderEventId(String provider, String providerEventId);

    List<InboxEventEntity> findByOrderIdOrderByReceivedAtAscIdAsc(UUID orderId);
}

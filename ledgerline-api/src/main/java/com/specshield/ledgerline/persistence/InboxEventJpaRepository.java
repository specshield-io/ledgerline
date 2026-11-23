package com.specshield.ledgerline.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, Long> {

    Optional<InboxEventEntity> findByProviderAndProviderEventId(String provider, String providerEventId);
}

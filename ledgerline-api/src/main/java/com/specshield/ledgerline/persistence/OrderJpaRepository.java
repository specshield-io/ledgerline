package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.domain.OrderStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

    Page<OrderEntity> findByStatus(OrderStatus status, Pageable pageable);
}

package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/** {@link OrderStore} backed by JPA. Transaction boundaries belong to the caller (application layer). */
@Repository
public class JpaOrderStore implements OrderStore {

    private final OrderJpaRepository orders;

    JpaOrderStore(OrderJpaRepository orders) {
        this.orders = orders;
    }

    @Override
    public Order insert(Order order) {
        return OrderMapper.toDomain(orders.saveAndFlush(OrderMapper.toNewEntity(order)));
    }

    @Override
    public Order update(Order order) {
        OrderEntity entity = orders.findById(order.id())
                .orElseThrow(() -> new IllegalArgumentException("order " + order.id() + " does not exist"));
        if (entity.getVersion() != order.version()) {
            throw new ObjectOptimisticLockingFailureException(OrderEntity.class, order.id());
        }
        OrderMapper.copyState(order, entity);
        return OrderMapper.toDomain(orders.saveAndFlush(entity));
    }

    @Override
    public Optional<Order> findByIdForUpdate(UUID id) {
        return orders.findByIdForUpdate(id).map(OrderMapper::toDomain);
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return orders.findById(id).map(OrderMapper::toDomain);
    }
}

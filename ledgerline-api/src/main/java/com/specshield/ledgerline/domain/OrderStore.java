package com.specshield.ledgerline.domain;

import java.util.Optional;
import java.util.UUID;

/** Port for storing orders. Implemented in {@code persistence}; the domain does not know how. */
public interface OrderStore {

    /** Inserts a new order. The returned order carries the stored version (0). */
    Order insert(Order order);

    /**
     * Updates an existing order using optimistic locking: {@code order.version()} must equal the stored version.
     * The returned order carries the incremented version.
     */
    Order update(Order order);

    Optional<Order> findById(UUID id);

    /**
     * Reads the order and locks it until the surrounding transaction ends (SELECT ... FOR UPDATE), so concurrent
     * event processing for one order is serialised.
     */
    Optional<Order> findByIdForUpdate(UUID id);
}

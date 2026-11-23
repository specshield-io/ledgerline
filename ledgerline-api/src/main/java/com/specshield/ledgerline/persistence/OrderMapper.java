package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.domain.Order;

/** Explicit, field-by-field mapping between {@link Order} and {@link OrderEntity}. No reflection magic. */
final class OrderMapper {

    private OrderMapper() {}

    static Order toDomain(OrderEntity e) {
        return new Order(
                e.getId(),
                e.getMerchantRef(),
                e.getAmountMinor(),
                e.getCurrency(),
                e.getStatus(),
                e.getRefundedMinor(),
                e.getLastEventSequence(),
                e.getVersion(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getLastProviderEventAt());
    }

    /** A new, never-persisted entity (version null). */
    static OrderEntity toNewEntity(Order order) {
        OrderEntity e = new OrderEntity(order.id());
        copyState(order, e);
        return e;
    }

    /** Copies every mutable field from the domain order, including the expected version. */
    static void copyState(Order order, OrderEntity e) {
        e.setMerchantRef(order.merchantRef());
        e.setAmountMinor(order.amountMinor());
        e.setCurrency(order.currency());
        e.setStatus(order.status());
        e.setRefundedMinor(order.refundedMinor());
        e.setLastEventSequence(order.lastEventSequence());
        e.setCreatedAt(order.createdAt());
        e.setUpdatedAt(order.updatedAt());
        e.setLastProviderEventAt(order.lastProviderEventAt());
    }
}

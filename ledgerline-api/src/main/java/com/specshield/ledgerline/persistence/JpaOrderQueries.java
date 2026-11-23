package com.specshield.ledgerline.persistence;

import com.specshield.ledgerline.application.InboxRecord;
import com.specshield.ledgerline.application.OrderQueries;
import com.specshield.ledgerline.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderQueries implements OrderQueries {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final OrderJpaRepository orders;
    private final InboxEventJpaRepository inbox;

    JpaOrderQueries(OrderJpaRepository orders, InboxEventJpaRepository inbox) {
        this.orders = orders;
        this.inbox = inbox;
    }

    @Override
    public OrderPage page(Optional<OrderStatus> status, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        Page<OrderEntity> result =
                status.map(s -> orders.findByStatus(s, request)).orElseGet(() -> orders.findAll(request));
        return new OrderPage(
                result.getContent().stream().map(OrderMapper::toDomain).toList(),
                page,
                size,
                result.getTotalElements());
    }

    @Override
    public List<InboxRecord> inboxForOrder(UUID orderId) {
        return inbox.findByOrderIdOrderByReceivedAtAscIdAsc(orderId).stream()
                .map(e -> new InboxRecord(
                        e.getId(),
                        e.getProvider(),
                        e.getProviderEventId(),
                        e.getPayload(),
                        e.isSignatureValid(),
                        e.getReceivedAt(),
                        e.getProcessedAt(),
                        e.getOutcome() == null ? null : e.getOutcome().name()))
                .toList();
    }
}

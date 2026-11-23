package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-side port: listing orders and reading inbox entries for the timeline. */
public interface OrderQueries {

    /** Orders newest first ({@code createdAt} desc, then id desc), optionally filtered by status. */
    OrderPage page(Optional<OrderStatus> status, int page, int size);

    /** Inbox entries linked to the order, oldest first. */
    List<InboxRecord> inboxForOrder(UUID orderId);

    record OrderPage(List<Order> items, int page, int size, long totalElements) {
        public OrderPage {
            items = List.copyOf(items);
        }

        public int totalPages() {
            return size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        }
    }
}

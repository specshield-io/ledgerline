package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns an order status change into a merchant notification in the outbox. Call it inside the transaction that
 * changes the order, so the notification exists if and only if the change commits.
 *
 * <p>Only outcomes a merchant acts on are announced: {@code order.paid}, {@code order.payment_failed},
 * {@code order.refunded}, {@code order.partially_refunded} and {@code order.cancelled}. PAYMENT_PENDING is an internal
 * step and produces no notification.
 */
@Component
public class MerchantNotifications {

    private final Outbox outbox;
    private final JsonMapper json;

    public MerchantNotifications(Outbox outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public static Optional<String> eventType(OrderStatus status) {
        return Optional.ofNullable(
                switch (status) {
                    case PAID -> "order.paid";
                    case PAYMENT_FAILED -> "order.payment_failed";
                    case REFUNDED -> "order.refunded";
                    case PARTIALLY_REFUNDED -> "order.partially_refunded";
                    case CANCELLED -> "order.cancelled";
                    case CREATED, PAYMENT_PENDING -> null;
                });
    }

    /** Appends a notification for {@code order}'s new status, if that status is announced. */
    public void statusChanged(OrderStatus previous, Order order, Instant at) {
        Optional<String> type = eventType(order.status());
        if (type.isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.id().toString());
        data.put("merchantRef", order.merchantRef());
        data.put("status", order.status().name());
        data.put("previousStatus", previous.name());
        data.put("amountMinor", order.amountMinor());
        data.put("refundedMinor", order.refundedMinor());
        data.put("currency", order.currency());
        // Microseconds, like every timestamp the database stores.
        data.put("occurredAt", at.truncatedTo(ChronoUnit.MICROS).toString());
        outbox.append(order.id(), type.get(), json.writeValueAsString(data), at);
    }
}

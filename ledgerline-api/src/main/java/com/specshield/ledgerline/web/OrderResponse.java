package com.specshield.ledgerline.web;

import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "Order")
public record OrderResponse(
        UUID id,
        String merchantRef,

        @Schema(description = "Amount in minor units (cents)")
        long amountMinor,

        String currency,
        OrderStatus status,

        @Schema(description = "Refunded so far, in minor units")
        long refundedMinor,

        @Schema(description = "Optimistic-locking version; increases on every change")
        long version,

        Instant createdAt,
        Instant updatedAt,

        @Schema(description = "Provider timestamp of the last applied payment event", nullable = true)
        Instant lastProviderEventAt) {

    static OrderResponse from(Order o) {
        return new OrderResponse(
                o.id(),
                o.merchantRef(),
                o.amountMinor(),
                o.currency(),
                o.status(),
                o.refundedMinor(),
                o.version(),
                o.createdAt(),
                o.updatedAt(),
                o.lastProviderEventAt());
    }
}

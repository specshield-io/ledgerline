package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OrderQueries.OrderPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "OrderPage", description = "Orders, newest first")
public record OrderPageResponse(List<OrderResponse> items, int page, int size, long totalElements, int totalPages) {

    static OrderPageResponse from(OrderPage p) {
        return new OrderPageResponse(
                p.items().stream().map(OrderResponse::from).toList(),
                p.page(),
                p.size(),
                p.totalElements(),
                p.totalPages());
    }
}

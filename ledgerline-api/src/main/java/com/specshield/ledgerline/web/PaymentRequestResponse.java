package com.specshield.ledgerline.web;

import com.specshield.ledgerline.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(
        name = "PaymentRequest",
        description = "The payment was requested from MockPay. The order status changes when MockPay reports events.")
public record PaymentRequestResponse(
        UUID orderId,

        @Schema(description = "Order status at the time of the request (unchanged by it)")
        OrderStatus status,

        @Schema(description = "MockPay's reference for the payment", example = "mp_stub_0194a1b2-...")
        String paymentReference) {}

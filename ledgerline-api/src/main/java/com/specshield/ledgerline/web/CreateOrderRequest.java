package com.specshield.ledgerline.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "A new order. Synthetic data only.")
public record CreateOrderRequest(
        @Schema(description = "Merchant's own reference for the order", example = "synthetic-order-1001")
        @NotBlank
        @Size(max = 64)
        String merchantRef,

        @Schema(description = "Amount in minor units (cents)", example = "2599", minimum = "1", maximum = "100000000")
        @NotNull
        @Min(1)
        @Max(100_000_000)
        Long amountMinor,

        @Schema(description = "ISO 4217 currency; only USD is supported", example = "USD", allowableValues = "USD")
        @NotNull
        @Pattern(regexp = "USD", message = "must be USD")
        String currency) {}

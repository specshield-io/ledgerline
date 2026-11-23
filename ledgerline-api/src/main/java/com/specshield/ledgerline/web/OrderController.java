package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OrderService;
import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Create, read, pay and cancel orders")
public class OrderController {

    private static final String PROBLEM = "application/problem+json";

    private static final String KEY_DESCRIPTION =
            "Unique key for this request (8-128 characters, [A-Za-z0-9_-]). Repeating the same request with the same"
                    + " key replays the stored response; the same key with a different request returns 422. Keys"
                    + " expire after 24 hours.";

    private final OrderService orders;
    private final JsonMapper json;

    public OrderController(OrderService orders, JsonMapper json) {
        this.orders = orders;
        this.json = json;
    }

    @PostMapping
    @Operation(
            summary = "Create an order",
            responses = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Created (or replayed)",
                        headers = {
                            @Header(name = "Location", description = "URL of the new order"),
                            @Header(name = "Idempotent-Replayed", description = "true when replayed")
                        },
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = OrderResponse.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Validation error, missing or invalid Idempotency-Key",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF))),
                @ApiResponse(
                        responseCode = "409",
                        description = "A request with this Idempotency-Key is in progress (Retry-After: 1)",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF))),
                @ApiResponse(
                        responseCode = "422",
                        description = "Idempotency-Key reused with a different request",
                        content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
            })
    ResponseEntity<OrderResponse> create(
            @Parameter(in = ParameterIn.HEADER, required = true, description = KEY_DESCRIPTION)
                    @RequestHeader(IdempotencyFilter.HEADER)
                    @Size(min = 8, max = 128)
                    @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        Order order = orders.create(request.merchantRef(), request.amountMinor(), request.currency());
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.id()))
                .body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order")
    @ApiResponse(
            responseCode = "200",
            description = "The order",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Order not found",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.from(orders.get(id));
    }

    @GetMapping
    @Operation(summary = "List orders, newest first")
    @ApiResponse(
            responseCode = "200",
            description = "A page of orders",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = OrderPageResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid status, page or size",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    OrderPageResponse list(
            @Parameter(description = "Only orders in this status") @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, at most 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100)
                    int size) {
        return OrderPageResponse.from(orders.list(Optional.ofNullable(status), page, size));
    }

    @PostMapping("/{id}/pay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Ask MockPay to start a payment",
            description = "Allowed for CREATED and PAYMENT_FAILED orders. The status changes later, when MockPay"
                    + " reports payment events.")
    @ApiResponse(
            responseCode = "404",
            description = "Order not found",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "409",
            description = "Order not payable in its state, or request with this key in progress",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "422",
            description = "Idempotency-Key reused with a different request",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    PaymentRequestResponse pay(
            @Parameter(in = ParameterIn.HEADER, required = true, description = KEY_DESCRIPTION)
                    @RequestHeader(IdempotencyFilter.HEADER)
                    @Size(min = 8, max = 128)
                    @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String idempotencyKey,
            @PathVariable UUID id) {
        OrderService.PaymentRequested requested = orders.pay(id);
        return new PaymentRequestResponse(
                requested.order().id(), requested.order().status(), requested.paymentReference());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order", description = "Only CREATED orders can be cancelled.")
    @ApiResponse(
            responseCode = "200",
            description = "The cancelled order (or the replayed response)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Order not found",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "409",
            description = "Order not cancellable in its state, concurrent change, or request in progress",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "422",
            description = "Idempotency-Key reused with a different request",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    OrderResponse cancel(
            @Parameter(in = ParameterIn.HEADER, required = true, description = KEY_DESCRIPTION)
                    @RequestHeader(IdempotencyFilter.HEADER)
                    @Size(min = 8, max = 128)
                    @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String idempotencyKey,
            @PathVariable UUID id) {
        return OrderResponse.from(orders.cancel(id));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Audit log and provider events for an order, oldest first")
    @ApiResponse(
            responseCode = "200",
            description = "Timeline entries, oldest first",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = TimelineEntryResponse.class))))
    @ApiResponse(
            responseCode = "404",
            description = "Order not found",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    List<TimelineEntryResponse> timeline(@PathVariable UUID id) {
        return orders.timeline(id).stream()
                .map(e -> new TimelineEntryResponse(
                        e.at(),
                        e.source(),
                        e.kind(),
                        json.readTree(e.detailJson()),
                        e.actor(),
                        e.correlationId(),
                        e.providerEventId(),
                        e.outcome()))
                .toList();
    }
}

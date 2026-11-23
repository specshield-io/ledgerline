package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.OutboxAdminService;
import com.specshield.ledgerline.application.OutboxStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operator view of the outbox. Listing needs the VIEWER or ADMIN role, replay needs ADMIN (HTTP Basic, see
 * config.SecurityConfig); the authenticated user is recorded as the audit actor.
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
@Tag(name = "Outbox administration", description = "Inspect merchant notifications and replay dead ones")
@SecurityRequirement(name = OpenApiSecurity.BASIC)
public class OutboxAdminController {

    private static final String PROBLEM = "application/problem+json";

    private final OutboxAdminService outbox;
    private final JsonMapper json;

    public OutboxAdminController(OutboxAdminService outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @GetMapping
    @Operation(summary = "List outbox messages, newest first (VIEWER or ADMIN)")
    @ApiResponse(
            responseCode = "200",
            description = "A page of outbox messages",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = OutboxPageResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid status, page or size",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    OutboxPageResponse list(
            @Parameter(description = "Only messages in this status") @RequestParam(required = false)
                    OutboxStatus status,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, at most 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100)
                    int size) {
        return OutboxPageResponse.from(outbox.list(Optional.ofNullable(status), page, size), json);
    }

    @PostMapping("/{id}/replay")
    @Operation(
            summary = "Replay a DEAD message",
            description = "Moves a DEAD message back to PENDING with its attempts reset; the dispatcher delivers it"
                    + " again. ADMIN only. Writes an OUTBOX_REPLAYED audit row with actor operator:<user>.")
    @ApiResponse(
            responseCode = "200",
            description = "The message, now PENDING (or the replayed response)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = OutboxMessageResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid Idempotency-Key",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "404",
            description = "Outbox message not found",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "409",
            description = "The message is not DEAD, or a request with this key is in progress",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "422",
            description = "Idempotency-Key reused with a different request",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    OutboxMessageResponse replay(
            @Parameter(
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Unique key for this request (8-128 characters, [A-Za-z0-9_-])")
                    @RequestHeader(IdempotencyFilter.HEADER)
                    @Size(min = 8, max = 128)
                    @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String idempotencyKey,
            Principal principal,
            @PathVariable long id) {
        return OutboxMessageResponse.from(outbox.replay(id, principal.getName()), json);
    }
}

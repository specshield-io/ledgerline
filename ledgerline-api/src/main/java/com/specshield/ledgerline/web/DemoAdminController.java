package com.specshield.ledgerline.web;

import com.specshield.ledgerline.application.DemoDataService;
import com.specshield.ledgerline.demo.DemoReset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Demo administration", description = "Reset the synthetic demo data")
@SecurityRequirement(name = OpenApiSecurity.BASIC)
public class DemoAdminController {

    private static final String PROBLEM = "application/problem+json";

    private final DemoReset reset;

    public DemoAdminController(DemoReset reset) {
        this.reset = reset;
    }

    @Schema(name = "DemoReset")
    public record ResetResponse(boolean reset, int seededOrders) {}

    @PostMapping("/api/v1/admin/reset")
    @Operation(
            summary = "Wipe and reseed the demo data (ADMIN)",
            description = "Truncates orders, inbox, audit log, outbox and the MockPay ledger, then seeds 25 synthetic"
                    + " orders. Same lock as the nightly 03:00 UTC reset; idempotent.")
    @ApiResponse(
            responseCode = "200",
            description = "Reset done",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResetResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "No or wrong credentials",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "403",
            description = "Not an ADMIN",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "409",
            description = "A reset is already running",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    ResetResponse resetDemoData(
            @Parameter(
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Unique key for this request (8-128 characters, [A-Za-z0-9_-])")
                    @RequestHeader(IdempotencyFilter.HEADER)
                    @Size(min = 8, max = 128)
                    @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String idempotencyKey,
            Principal principal) {
        if (reset.reset("admin:" + principal.getName()) == DemoReset.Outcome.ALREADY_RUNNING) {
            throw new ResetInProgressException();
        }
        return new ResetResponse(true, DemoDataService.SEEDED_ORDERS);
    }

    static final class ResetInProgressException extends RuntimeException {
        ResetInProgressException() {
            super("A demo data reset is already running. Try again in a minute.");
        }
    }
}

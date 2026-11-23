package com.specshield.ledgerline.webhook;

import com.specshield.ledgerline.application.DecodedEvent;
import com.specshield.ledgerline.application.PaymentEventService;
import com.specshield.ledgerline.application.ProviderEventDecoder.MalformedEventException;
import com.specshield.ledgerline.web.ProblemResponses;
import com.specshield.ledgerline.web.ProblemTypes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives MockPay webhooks. The body is taken as raw bytes ({@code byte[]}), so nothing parses it before the
 * signature is verified, and the bytes that are verified are exactly the bytes that are decoded.
 */
@RestController
@Tag(name = "Webhooks", description = "Inbound provider webhooks (called by MockPay, not by clients)")
public class MockPayWebhookController {

    public static final String PATH = "/webhooks/mockpay";
    public static final String SIGNATURE_HEADER = "MockPay-Signature";
    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(MockPayWebhookController.class);
    private static final String PROBLEM = "application/problem+json";

    private final WebhookSignatureVerifier verifier;
    private final MockPayEventDecoder decoder;
    private final PaymentEventService events;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public MockPayWebhookController(
            WebhookSignatureVerifier verifier,
            MockPayEventDecoder decoder,
            PaymentEventService events,
            WebhookMetrics metrics,
            Clock clock) {
        this.verifier = verifier;
        this.decoder = decoder;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    @PostMapping(path = PATH, consumes = "application/json", produces = "application/json")
    @Operation(
            summary = "Receive a MockPay event",
            description = "Signed with MockPay-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of \"t.body\">."
                    + " Duplicates are acknowledged with 200 and duplicate=true so the provider stops retrying.")
    @ApiResponse(
            responseCode = "200",
            description = "Received (applied, duplicate, ignored, orphan or waiting)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WebhookAck.class)))
    @ApiResponse(
            responseCode = "400",
            description = "invalid-signature or malformed-event",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    @ApiResponse(
            responseCode = "500",
            description = "Processing failed and was rolled back; the provider should retry",
            content = @Content(mediaType = PROBLEM, schema = @Schema(ref = ProblemTypes.SCHEMA_REF)))
    ResponseEntity<?> receive(
            @Parameter(in = ParameterIn.HEADER, description = "t=<unix seconds>,v1=<hex>[,v1=<hex>]")
                    @RequestHeader(name = SIGNATURE_HEADER, required = false)
                    String signature,
            @RequestBody byte[] body,
            HttpServletRequest request) {
        metrics.received();
        return metrics.processing().record(() -> handle(signature, body, request));
    }

    private ResponseEntity<?> handle(String signature, byte[] body, HttpServletRequest request) {
        if (body.length > MAX_BODY_BYTES) {
            return reject("body_too_large", body, request);
        }
        try {
            verifier.verify(signature, body, clock.instant());
        } catch (InvalidSignatureException e) {
            return reject(e.reason().tag(), body, request);
        }

        String payload = new String(body, StandardCharsets.UTF_8);
        DecodedEvent event;
        try {
            event = decoder.decode(payload);
        } catch (MalformedEventException e) {
            metrics.rejected("malformed_event");
            log.info("mockpay webhook rejected: malformed event ({})", e.getMessage());
            return problem(
                    HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_EVENT, "Malformed event", e.getMessage(), request);
        }

        PaymentEventService.Result result;
        try {
            result = events.process(event, payload);
        } catch (RuntimeException e) {
            metrics.failure();
            // The stack trace is logged once, by ApiExceptionHandler.
            log.error(
                    "mockpay webhook {} failed ({}); rolled back for redelivery",
                    event.eventId(),
                    e.getClass().getSimpleName());
            throw e; // ApiExceptionHandler answers 500 problem+json
        }

        metrics.applied(result.applied());
        metrics.stale(result.stale());
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("received", true);
        if (result.outcome() == PaymentEventService.Outcome.DUPLICATE) {
            metrics.duplicate();
            ack.put("duplicate", true);
        }
        log.info("mockpay webhook {} ({}) -> {}", event.eventId(), event.type(), result.outcome());
        return ResponseEntity.ok(ack);
    }

    private ResponseEntity<ProblemDetail> reject(String reason, byte[] body, HttpServletRequest request) {
        metrics.rejected(reason);
        events.reject(reason, body);
        // Log the reason only: never the secret, the signature header or the body.
        log.info("mockpay webhook rejected: {}", reason);
        return problem(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.INVALID_SIGNATURE,
                "Invalid signature",
                "The MockPay-Signature header is missing, malformed, expired or does not match.",
                request);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, URI type, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemResponses.problem(status, type, title, detail, request.getRequestURI()));
    }

    @Schema(name = "WebhookAck")
    record WebhookAck(
            boolean received,

            @Schema(nullable = true, description = "Present and true when the event was already stored")
            Boolean duplicate) {}
}

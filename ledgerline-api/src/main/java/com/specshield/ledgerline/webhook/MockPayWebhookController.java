package com.specshield.ledgerline.webhook;

import com.specshield.ledgerline.application.DecodedEvent;
import com.specshield.ledgerline.application.PaymentEventService;
import com.specshield.ledgerline.application.ProviderEventDecoder.MalformedEventException;
import com.specshield.ledgerline.web.ProblemResponses;
import com.specshield.ledgerline.web.ProblemTypes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import org.slf4j.MDC;
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
    /** MDC key for the order a log line is about (in JSON logs as orderId). */
    public static final String ORDER_ID_MDC_KEY = "orderId";

    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(MockPayWebhookController.class);
    private static final String PROBLEM = "application/problem+json";

    private final WebhookSignatureVerifier verifier;
    private final MockPayEventDecoder decoder;
    private final PaymentEventService events;
    private final WebhookMetrics metrics;
    private final ObservationRegistry observations;
    private final Clock clock;

    public MockPayWebhookController(
            WebhookSignatureVerifier verifier,
            MockPayEventDecoder decoder,
            PaymentEventService events,
            WebhookMetrics metrics,
            ObservationRegistry observations,
            Clock clock) {
        this.verifier = verifier;
        this.decoder = decoder;
        this.events = events;
        this.metrics = metrics;
        this.observations = observations;
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
        try {
            return metrics.processing().record(() -> handle(signature, body, request));
        } finally {
            MDC.remove(ORDER_ID_MDC_KEY);
        }
    }

    private ResponseEntity<?> handle(String signature, byte[] body, HttpServletRequest request) {
        if (body.length > MAX_BODY_BYTES) {
            return reject("body_too_large", body, request);
        }
        String rejection = verify(signature, body);
        if (rejection != null) {
            return reject(rejection, body, request);
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

        MDC.put(ORDER_ID_MDC_KEY, String.valueOf(event.orderId()));
        PaymentEventService.Result result;
        try {
            result = process(event, payload);
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

    /** Span/timer {@code webhook.verify}; returns the rejection reason tag, or null if the signature is valid. */
    private String verify(String signature, byte[] body) {
        Observation observation = Observation.createNotStarted("webhook.verify", observations)
                .contextualName("webhook verify")
                .lowCardinalityKeyValue("provider", MockPayEventDecoder.PROVIDER);
        return observation.observe(() -> {
            try {
                verifier.verify(signature, body, clock.instant());
                observation.lowCardinalityKeyValue("result", "valid");
                return null;
            } catch (InvalidSignatureException e) {
                observation.lowCardinalityKeyValue("result", e.reason().tag());
                return e.reason().tag();
            }
        });
    }

    /** Span/timer {@code webhook.process}: inbox, sequencing, order update, audit and outbox in one transaction. */
    private PaymentEventService.Result process(DecodedEvent event, String payload) {
        Observation observation = Observation.createNotStarted("webhook.process", observations)
                .contextualName("webhook process")
                .lowCardinalityKeyValue("provider", MockPayEventDecoder.PROVIDER)
                .lowCardinalityKeyValue("event.type", event.knownType() ? event.type() : "unknown")
                .highCardinalityKeyValue("event.id", event.eventId())
                .highCardinalityKeyValue("order.id", String.valueOf(event.orderId()));
        return observation.observe(() -> {
            PaymentEventService.Result result = events.process(event, payload);
            observation.lowCardinalityKeyValue("outcome", result.outcome().name());
            return result;
        });
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

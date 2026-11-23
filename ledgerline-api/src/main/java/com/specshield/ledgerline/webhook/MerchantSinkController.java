package com.specshield.ledgerline.webhook;

import com.specshield.ledgerline.outbox.MerchantDeliveryClient;
import com.specshield.ledgerline.web.ProblemResponses;
import com.specshield.ledgerline.web.ProblemTypes;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The demo merchant endpoint ({@code ledgerline.merchant.sink.enabled}). Verifies {@code Ledgerline-Signature} over
 * the raw bytes like a real merchant should, records every request, and answers according to
 * {@link MerchantSink#mode()}. Hidden from the OpenAPI document: it simulates a third party, it is not our API.
 */
@Hidden
@RestController
@ConditionalOnProperty(name = "ledgerline.merchant.sink.enabled", havingValue = "true")
public class MerchantSinkController {

    public static final String PATH = "/demo/merchant-sink";
    static final int MAX_BODY_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(MerchantSinkController.class);

    private final MerchantSink sink;
    private final JsonMapper json;

    public MerchantSinkController(MerchantSink sink, JsonMapper json) {
        this.sink = sink;
        this.json = json;
    }

    @PostMapping(PATH)
    ResponseEntity<?> receive(
            @RequestHeader(name = MerchantDeliveryClient.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(name = MerchantDeliveryClient.EVENT_ID_HEADER, required = false) String eventId,
            @RequestHeader(name = MerchantDeliveryClient.ATTEMPT_HEADER, required = false) String attempt,
            @RequestBody byte[] body,
            HttpServletRequest request)
            throws InterruptedException {
        boolean valid = body.length <= MAX_BODY_BYTES;
        if (valid) {
            try {
                sink.verifier().verify(signature, body, sink.clock().instant());
            } catch (InvalidSignatureException e) {
                valid = false;
            }
        }
        String type = valid ? eventType(body) : null;
        if (!valid) {
            sink.record(eventId, attempt, null, false, 400);
            log.info("merchant sink rejected a delivery: invalid signature");
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemResponses.problem(
                            HttpStatus.BAD_REQUEST,
                            ProblemTypes.INVALID_SIGNATURE,
                            "Invalid signature",
                            "The Ledgerline-Signature header is missing, malformed, expired or does not match.",
                            request.getRequestURI()));
        }
        switch (sink.mode()) {
            case FAIL -> {
                sink.record(eventId, attempt, type, true, 500);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(ProblemResponses.problem(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                ProblemTypes.INTERNAL_ERROR,
                                "Merchant unavailable",
                                "The demo merchant is configured to fail.",
                                request.getRequestURI()));
            }
            case SLOW -> Thread.sleep(sink.slowDelay());
            case OK -> {
                // answer immediately
            }
        }
        boolean duplicate = sink.record(eventId, attempt, type, true, 200);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("received", true);
        if (duplicate) {
            ack.put("duplicate", true);
        }
        return ResponseEntity.ok(ack);
    }

    @GetMapping(PATH)
    Map<String, Object> deliveries() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("mode", sink.mode());
        List<MerchantSink.Delivery> deliveries = sink.deliveries();
        view.put("deliveries", deliveries);
        return view;
    }

    private String eventType(byte[] body) {
        try {
            JsonNode type = json.readTree(body).get("type");
            return type == null || !type.isString() ? null : type.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

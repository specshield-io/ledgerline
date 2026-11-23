package com.specshield.ledgerline.webhook;

import com.specshield.ledgerline.application.DecodedEvent;
import com.specshield.ledgerline.application.ProviderEventDecoder;
import com.specshield.ledgerline.domain.Money;
import com.specshield.ledgerline.domain.PaymentEvent;
import com.specshield.ledgerline.domain.PaymentEventType;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decodes MockPay's event JSON: {@code {id, type, created, sequence, data: {orderId, amountMinor, currency}}}.
 * Only called after the signature was verified. Unknown types decode with {@code event == null}; anything that is
 * not a well-formed event is a {@link MalformedEventException}.
 */
@Component
public class MockPayEventDecoder implements ProviderEventDecoder {

    public static final String PROVIDER = "mockpay";
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,255}");

    private final JsonMapper json;

    public MockPayEventDecoder(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public DecodedEvent decode(String payloadJson) {
        JsonNode root;
        try {
            root = json.readTree(payloadJson);
        } catch (JacksonException e) {
            throw new MalformedEventException("body is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedEventException("body is not a JSON object");
        }
        String id = text(root, "id");
        if (id == null || !EVENT_ID.matcher(id).matches()) {
            throw new MalformedEventException("id is missing or invalid");
        }
        String type = text(root, "type");
        if (type == null || type.length() > 64) {
            throw new MalformedEventException("type is missing or invalid");
        }
        JsonNode data = root.get("data");
        UUID orderId = data != null && data.isObject() ? uuid(text(data, "orderId")) : null;

        boolean known =
                Arrays.stream(PaymentEventType.values()).anyMatch(t -> t.name().equals(type));
        if (!known) {
            return new DecodedEvent(id, type, orderId, null);
        }
        if (orderId == null) {
            throw new MalformedEventException("data.orderId is missing or invalid");
        }
        long created = integral(root, "created");
        long sequence = integral(root, "sequence");
        long amount = integral(data, "amountMinor");
        if (!Money.USD.equals(text(data, "currency"))) {
            throw new MalformedEventException("data.currency must be USD");
        }
        try {
            PaymentEvent event = new PaymentEvent(
                    id, PaymentEventType.valueOf(type), orderId, amount, Instant.ofEpochSecond(created), sequence);
            return new DecodedEvent(id, type, orderId, event);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new MalformedEventException("event fields are out of range");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static long integral(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MalformedEventException(field + " must be an integer");
        }
        return value.asLong();
    }

    private static UUID uuid(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

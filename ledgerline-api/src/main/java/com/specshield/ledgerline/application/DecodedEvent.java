package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.PaymentEvent;
import java.util.UUID;

/**
 * A provider event after its signature was verified and its JSON decoded.
 *
 * @param type the provider's type string, as sent
 * @param orderId the order it refers to; null only if an unknown event type carries none
 * @param event the domain event, or null when {@code type} is not one Ledgerline understands
 */
public record DecodedEvent(String eventId, String type, UUID orderId, PaymentEvent event) {

    public boolean knownType() {
        return event != null;
    }
}

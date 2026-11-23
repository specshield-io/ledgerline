package com.specshield.ledgerline.application;

/** Port: turns a provider's raw event payload into a {@link DecodedEvent}. */
public interface ProviderEventDecoder {

    String provider();

    /** @throws MalformedEventException if the payload is not a valid event */
    DecodedEvent decode(String payloadJson);

    final class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message) {
            super(message);
        }
    }
}

package com.specshield.ledgerline.outbox;

/** Outcome of one HTTP delivery attempt. */
public sealed interface DeliveryResult {

    /** The merchant answered 2xx. */
    record Delivered(int status) implements DeliveryResult {}

    /**
     * Anything else.
     *
     * @param reason low-cardinality metric tag: {@code http_4xx}, {@code http_5xx}, {@code http_other},
     *     {@code timeout}, {@code connect}, {@code io}, {@code interrupted}
     * @param message stored as the row's last error; never contains secrets or the response body
     */
    record Failed(String reason, String message) implements DeliveryResult {}
}
